package com.tripbus.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.payment.WebhookSigner;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.service.TicketMailRetryJob;
import jakarta.mail.BodyPart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-033: al aprobarse el pago se envía el tiquete por correo con el QR en PNG adjunto. SMTP en memoria
 * (GreenMail), PostgreSQL y Redis reales (Testcontainers); los demás servicios y la pasarela son mocks.
 */
@SpringBootTest(properties = "tripbus.jobs.ticket-mail-retry-initial-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class TicketMailIT {

    private static final String EMAIL = "ana.perez@mail.com";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final GreenMail smtp = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    static {
        smtp.start();
    }

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("tripbus.mail.host", () -> "localhost");
        registry.add("tripbus.mail.port", () -> smtp.getSmtp().getPort());
    }

    @AfterAll
    void detenerSmtp() {
        smtp.stop();
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private WebhookSigner signer;
    @Autowired
    private TicketRepository tickets;
    @Autowired
    private TicketMailRetryJob retryJob;

    @MockitoSpyBean
    private JavaMailSender mailSender;
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
    void preparar() throws Exception {
        smtp.purgeEmailFromAllMailboxes();
        reset(seatInventory, catalog, gateway, mailSender);
        when(catalog.price(itineraryId)).thenReturn(
                new CatalogClient.Price(itineraryId, new BigDecimal("95000"), "COP", "PROGRAMADO"));
        when(catalog.itinerary(itineraryId)).thenReturn(new CatalogClient.ItinerarySummary(itineraryId, "Bogotá",
                "Medellín", Instant.parse("2026-12-20T13:30:00Z"), null, "Expreso TripBus", "ABC123"));
        when(gateway.charge(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> "sbx_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private RequestPostProcessor pasajero(String email) {
        return jwt().jwt(j -> {
            j.subject(userId.toString()).claim("role", "PASAJERO");
            if (email != null) {
                j.claim("email", email);
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"));
    }

    /** Crea la reserva con el token dado, paga y aprueba el webhook. Devuelve el tiquete emitido. */
    private Ticket comprar(String email) throws Exception {
        UUID holdId = UUID.randomUUID();
        when(seatInventory.getHold(holdId)).thenReturn(new SeatInventoryClient.Hold(holdId, itineraryId, List.of("12"),
                userId, null, null, "ACTIVA", Instant.now().plusSeconds(600), 600));
        String reserva = mvc.perform(post("/bookings").with(pasajero(email)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"%s","pasajeros":[{"nombre":"Ana <Pérez>","tipoDocumento":"CC","numeroDocumento":"1020304050"}]}
                                """.formatted(holdId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(objectMapper.readTree(reserva).get("bookingId").asText());
        when(seatInventory.confirmSale(holdId, bookingId)).thenReturn(new SeatInventoryClient.Hold(holdId, itineraryId,
                List.of("12"), userId, null, bookingId, "CONFIRMADA", Instant.now(), 0));

        String pago = mvc.perform(post("/bookings/{id}/pay", bookingId).with(pasajero(email))
                        .header("Idempotency-Key", "pago-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardToken\":\"tok_visa_approved\",\"cardLast4\":\"4242\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String body = objectMapper.writeValueAsString(Map.of(
                "eventId", "evt_" + UUID.randomUUID(), "reference", objectMapper.readTree(pago).get("paymentId").asText(),
                "status", "APPROVED", "amount", 95000, "currency", "COP", "timestamp", Instant.now().toString()));
        mvc.perform(post("/webhooks/payment").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signer.sign(body)).content(body))
                .andExpect(status().isOk());

        mvc.perform(get("/bookings/{id}", bookingId).with(pasajero(email)))
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"))
                .andExpect(jsonPath("$.pago.estado").value("APROBADO"));
        return tickets.findByBookingId(bookingId).orElseThrow();
    }

    private Ticket releer(Ticket ticket) {
        return tickets.findById(ticket.getId()).orElseThrow();
    }

    private static byte[] adjuntoPng(MimeMessage message) throws Exception {
        MimeMultipart multipart = (MimeMultipart) message.getContent();
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                assertThat(part.getContentType()).startsWith("image/png");
                assertThat(part.getFileName()).endsWith(".png");
                try (InputStream in = part.getInputStream()) {
                    return in.readAllBytes();
                }
            }
        }
        return null;
    }

    @Test
    void alAprobarElPagoLlegaElCorreoConElQrAdjunto() throws Exception {
        Ticket ticket = comprar(EMAIL);

        await().atMost(Duration.ofSeconds(10)).until(() -> smtp.getReceivedMessages().length == 1);
        MimeMessage correo = smtp.getReceivedMessages()[0];
        assertThat(correo.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        assertThat(correo.getFrom()[0].toString()).contains("no-reply@tripbus.co");
        assertThat(correo.getSubject()).isEqualTo("Tu tiquete TripBus · Bogotá → Medellín");
        byte[] png = adjuntoPng(correo);
        assertThat(png).isNotNull();
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');

        await().atMost(Duration.ofSeconds(5)).until(() -> releer(ticket).getNotificadoEn() != null);
        assertThat(releer(ticket).getIntentosNotificacion()).isZero();
        // Ya notificado: el job no lo vuelve a enviar.
        assertThat(retryJob.retryNow()).isZero();
        assertThat(smtp.getReceivedMessages()).hasSize(1);
    }

    @Test
    void sinEmailEnElTokenNoSeEnviaCorreo() throws Exception {
        Ticket ticket = comprar(null);

        assertThat(retryJob.retryNow()).isZero();
        Thread.sleep(500); // margen para un (indebido) envío en segundo plano
        assertThat(smtp.getReceivedMessages()).isEmpty();
        assertThat(releer(ticket).getNotificadoEn()).isNull();
        assertThat(releer(ticket).getIntentosNotificacion()).isZero();
    }

    @Test
    void siElSmtpFallaSumaUnIntentoYElJobLoReintenta() throws Exception {
        doThrow(new MailSendException("SMTP caído")).when(mailSender).send(any(MimeMessage.class));

        Ticket ticket = comprar(EMAIL); // la compra termina CONFIRMADA aunque el correo falle

        await().atMost(Duration.ofSeconds(10)).until(() -> releer(ticket).getIntentosNotificacion() == 1);
        assertThat(releer(ticket).getNotificadoEn()).isNull();
        assertThat(smtp.getReceivedMessages()).isEmpty();

        reset(mailSender); // el SMTP se recupera
        assertThat(retryJob.retryNow()).isEqualTo(1);
        assertThat(smtp.getReceivedMessages()).hasSize(1);
        assertThat(releer(ticket).getNotificadoEn()).isNotNull();
    }

    @Test
    void conCincoIntentosFallidosElJobDejaDeReintentar() throws Exception {
        doThrow(new MailSendException("SMTP caído")).when(mailSender).send(any(MimeMessage.class));
        Ticket ticket = comprar(EMAIL);
        await().atMost(Duration.ofSeconds(10)).until(() -> releer(ticket).getIntentosNotificacion() == 1);

        for (int i = 0; i < 4; i++) {
            retryJob.retryNow();
        }
        assertThat(releer(ticket).getIntentosNotificacion()).isEqualTo(5);

        reset(mailSender);
        assertThat(retryJob.retryNow()).isZero();
        assertThat(smtp.getReceivedMessages()).isEmpty();
        assertThat(releer(ticket).getNotificadoEn()).isNull();
    }
}
