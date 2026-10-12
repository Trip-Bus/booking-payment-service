package com.tripbus.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.payment.WebhookSigner;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.repository.WebhookLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saga de compra completa contra PostgreSQL 16 y Redis 7 reales (Testcontainers, requiere Docker):
 * Flyway aplica las migraciones REALES de db/migration sobre una base nueva en cada ejecución.
 * Seat Inventory, Catalog, Flash Offers y la pasarela se simulan con mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class BookingSagaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private WebhookSigner signer;
    @Autowired
    private WebhookLogRepository webhookLogs;
    @Autowired
    private TicketRepository tickets;

    @MockitoBean
    private SeatInventoryClient seatInventory;
    @MockitoBean
    private CatalogClient catalog;
    @MockitoBean
    private FlashOffersClient flashOffers;
    @MockitoBean
    private PaymentGateway gateway;

    private final UUID userId = UUID.randomUUID();
    private final UUID itineraryId = UUID.randomUUID();

    @BeforeEach
    void simularServicios() {
        reset(seatInventory, catalog, gateway);
        when(catalog.price(itineraryId)).thenReturn(
                new CatalogClient.Price(itineraryId, new BigDecimal("95000"), "COP", "PROGRAMADO"));
        when(gateway.charge(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> "sbx_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private UUID nuevaRetencion() {
        UUID holdId = UUID.randomUUID();
        when(seatInventory.getHold(holdId)).thenReturn(new SeatInventoryClient.Hold(holdId, itineraryId, List.of("12"),
                userId, null, null, "ACTIVA", Instant.now().plusSeconds(600), 600));
        return holdId;
    }

    private RequestPostProcessor pasajero() {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("role", "PASAJERO")
                        .claim("email", "ana.perez@mail.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"));
    }

    private JsonNode crearReserva(UUID holdId) throws Exception {
        String body = """
                {"holdId":"%s","pasajeros":[{"nombre":"Ana Pérez","tipoDocumento":"CC","numeroDocumento":"1020304050"}]}
                """.formatted(holdId);
        String response = mvc.perform(post("/bookings").with(pasajero())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("PENDIENTE_PAGO"))
                .andExpect(jsonPath("$.total").value(95000))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String pagar(UUID bookingId, String key, String token) throws Exception {
        return mvc.perform(post("/bookings/{id}/pay", bookingId).with(pasajero())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardToken\":\"" + token + "\",\"cardLast4\":\"4242\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private void webhook(String paymentId, String status) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "eventId", "evt_" + UUID.randomUUID(), "reference", paymentId, "status", status,
                "amount", 95000, "timestamp", Instant.now().toString()));
        mvc.perform(post("/webhooks/payment").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signer.sign(body)).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void compraCompletaConReintentosYWebhookDuplicadoEmiteUnSoloTiquete() throws Exception {
        UUID holdId = nuevaRetencion();
        UUID bookingId = UUID.fromString(crearReserva(holdId).get("bookingId").asText());

        // Cinco reintentos concurrentes del mismo pago (mismo Idempotency-Key).
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<String>> intentos = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            intentos.add(pool.submit(() -> pagar(bookingId, "pago-reserva-0001", "tok_visa_approved")));
        }
        java.util.Set<String> paymentIds = new java.util.HashSet<>();
        for (Future<String> f : intentos) {
            JsonNode r = objectMapper.readTree(f.get());
            if (r.has("paymentId")) {
                paymentIds.add(r.get("paymentId").asText());
            }
        }
        pool.shutdown();
        assertThat(paymentIds).hasSize(1);
        verify(gateway, times(1)).charge(any(), any(), anyString(), anyString());

        // Un reintento después devuelve la respuesta guardada.
        mvc.perform(post("/bookings/{id}/pay", bookingId).with(pasajero())
                        .header("Idempotency-Key", "pago-reserva-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardToken\":\"tok_visa_approved\",\"cardLast4\":\"4242\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        String paymentId = paymentIds.iterator().next();
        when(seatInventory.confirmSale(holdId, bookingId)).thenReturn(new SeatInventoryClient.Hold(holdId, itineraryId,
                List.of("12"), userId, null, bookingId, "CONFIRMADA", Instant.now(), 0));
        webhook(paymentId, "APPROVED");
        webhook(paymentId, "APPROVED"); // la pasarela reintenta el webhook

        verify(seatInventory, times(1)).confirmSale(holdId, bookingId);
        String booking = mvc.perform(get("/bookings/{id}", bookingId).with(pasajero()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"))
                .andExpect(jsonPath("$.pago.estado").value("APROBADO"))
                .andReturn().getResponse().getContentAsString();
        String ticketId = objectMapper.readTree(booking).get("ticketId").asText();

        // US-033: sin MAIL_HOST (perfil it) el correo está deshabilitado: la compra termina igual y no se intenta.
        var ticket = tickets.findById(UUID.fromString(ticketId)).orElseThrow();
        assertThat(ticket.getNotificadoEn()).isNull();
        assertThat(ticket.getIntentosNotificacion()).isZero();

        mvc.perform(get("/tickets/{id}", ticketId).with(pasajero()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrImage").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,")))
                .andExpect(jsonPath("$.seatNumbers[0]").value("12"));
    }

    @Test
    void mismaLlaveConOtroCuerpoEsRechazada() throws Exception {
        UUID bookingId = UUID.fromString(crearReserva(nuevaRetencion()).get("bookingId").asText());
        pagar(bookingId, "llave-reutilizada-1", "tok_visa_approved");
        mvc.perform(post("/bookings/{id}/pay", bookingId).with(pasajero())
                        .header("Idempotency-Key", "llave-reutilizada-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardToken\":\"otro_token\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        // Y otra llave distinta tampoco genera un segundo cobro.
        pagar(bookingId, "llave-distinta-22", "tok_visa_approved");
        verify(gateway, times(1)).charge(any(), any(), anyString(), anyString());
    }

    @Test
    void pagoRechazadoLiberaLaRetencion() throws Exception {
        UUID holdId = nuevaRetencion();
        UUID bookingId = UUID.fromString(crearReserva(holdId).get("bookingId").asText());
        String paymentId = objectMapper.readTree(pagar(bookingId, "pago-rechazado-01", "tok_visa_declined"))
                .get("paymentId").asText();
        webhook(paymentId, "DECLINED");
        verify(seatInventory).releaseHold(eq(holdId), anyString());
        mvc.perform(get("/bookings/{id}", bookingId).with(pasajero()))
                .andExpect(jsonPath("$.estado").value("PAGO_RECHAZADO"));
    }

    @Test
    void siElAsientoSePerdioDespuesDelPagoSeReembolsa() throws Exception {
        UUID holdId = nuevaRetencion();
        UUID bookingId = UUID.fromString(crearReserva(holdId).get("bookingId").asText());
        String paymentId = objectMapper.readTree(pagar(bookingId, "pago-tardio-0001", "tok_visa_approved"))
                .get("paymentId").asText();
        when(seatInventory.confirmSale(holdId, bookingId))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "expirada"));
        webhook(paymentId, "APPROVED");
        mvc.perform(get("/bookings/{id}", bookingId).with(pasajero()))
                .andExpect(jsonPath("$.estado").value("REEMBOLSADA"))
                .andExpect(jsonPath("$.pago.estado").value("REEMBOLSADO"));
    }

    @Test
    void webhookConFirmaInvalidaSeRechaza() throws Exception {
        long antes = webhookLogs.count();
        mvc.perform(post("/webhooks/payment").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "sha256=falsa").content("{\"reference\":\"x\",\"status\":\"APPROVED\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_SIGNATURE"));
        // El intento rechazado queda registrado en webhook_log (auditoría).
        assertThat(webhookLogs.count()).isEqualTo(antes + 1);
    }

    @Test
    void otroUsuarioNoPuedeVerLaReserva() throws Exception {
        UUID bookingId = UUID.fromString(crearReserva(nuevaRetencion()).get("bookingId").asText());
        mvc.perform(get("/bookings/{id}", bookingId).with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/bookings/{id}", bookingId)).andExpect(status().isUnauthorized());
    }
}
