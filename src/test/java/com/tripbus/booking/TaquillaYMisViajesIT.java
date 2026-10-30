package com.tripbus.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.IdentityClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.service.AffectedBookingService;
import com.tripbus.booking.service.BookingService;
import com.tripbus.booking.service.NoShowJob;

import com.tripbus.booking.service.TicketService;
import com.tripbus.booking.service.TripDataBackfill;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
 * Sprint 4: venta en taquilla (US-144), mis viajes, reenvío y PDF (US-145), no-show (US-153) y reglas de la tarifa de
 * oferta (US-166), contra PostgreSQL 16 y Redis 7 reales (Testcontainers) con las migraciones Flyway reales y un SMTP
 * en memoria (GreenMail). Seat Inventory, Catalog, Flash Offers, Identity y la pasarela se simulan con mocks.
 */
@SpringBootTest(properties = {"tripbus.jobs.ticket-mail-retry-initial-ms=3600000",
        "tripbus.booking.trip-backfill-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class TaquillaYMisViajesIT {

    private static final String COMPRADOR = "juan.gomez@mail.com";
    private static final java.time.ZoneId BOGOTA = java.time.ZoneId.of("America/Bogota");

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
    private BookingRepository bookings;
    @Autowired
    private PassengerRepository passengers;
    @Autowired
    private TicketRepository tickets;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private AffectedBookingService affectedService;
    @Autowired
    private NoShowJob noShowJob;
    @Autowired
    private TripDataBackfill backfill;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private SeatInventoryClient seatInventory;
    @MockitoBean
    private CatalogClient catalog;
    @MockitoBean
    private FlashOffersClient flashOffers;
    @MockitoBean
    private IdentityClient identity;
    @MockitoBean
    private PaymentGateway gateway;

    private UUID companyId;
    private UUID operadorId;
    private UUID userId;

    @BeforeEach
    void preparar() {
        reset(seatInventory, catalog, flashOffers, identity, gateway);
        companyId = UUID.randomUUID();
        operadorId = UUID.randomUUID();
        userId = UUID.randomUUID();
        when(identity.passengerIdByEmail(anyString())).thenReturn(Optional.empty());
        when(seatInventory.confirmSale(any(), any())).thenAnswer(inv -> new SeatInventoryClient.Hold(inv.getArgument(0),
                null, List.of(), null, null, null, "CONFIRMADA", Instant.now(), 0));
    }

    // ------------------------------------------------------------------ utilidades

    /** Viaje en catalog de {@code empresa} que sale dentro de {@code enCuanto} (negativo: ya salió). */
    private UUID viaje(UUID empresa, Duration enCuanto, String estado) {
        UUID id = UUID.randomUUID();
        CatalogClient.ItinerarySummary resumen = new CatalogClient.ItinerarySummary(id, "Bogotá", "Medellín",
                Instant.now().plus(enCuanto), null, "Expreso Andino", "ABC123", empresa, estado);
        when(catalog.itinerary(id)).thenReturn(resumen);
        when(catalog.requireItinerary(id)).thenReturn(resumen);
        when(catalog.price(id)).thenReturn(new CatalogClient.Price(id, new BigDecimal("95000"), "COP", estado));
        return id;
    }

    /** seat-inventory retiene lo que se le pida en {@code itineraryId}. */
    private void retenciones(UUID itineraryId) {
        when(seatInventory.createHold(eq(itineraryId), anyList(), any())).thenAnswer(inv ->
                new SeatInventoryClient.Hold(UUID.randomUUID(), itineraryId, inv.getArgument(1), inv.getArgument(2),
                        null, null, "ACTIVA", Instant.now().plusSeconds(600), 600));
    }

    /** Retención activa para reprogramar. */
    private UUID retencion(UUID itineraryId, UUID dueno) {
        UUID holdId = UUID.randomUUID();
        when(seatInventory.getHold(holdId)).thenReturn(new SeatInventoryClient.Hold(holdId, itineraryId, List.of("20"),
                dueno, null, null, "ACTIVA", Instant.now().plusSeconds(600), 600));
        return holdId;
    }

    /** Reserva CONFIRMADA de 95.000 con tiquete EMITIDO, asiento 12, que sale en {@code salida}. */
    private Ticket comprada(UUID dueno, UUID itineraryId, Instant salida, UUID offerId) {
        return tx.execute(s -> {
            Booking b = new Booking(UUID.randomUUID(), dueno, itineraryId, offerId, List.of("12"),
                    new BigDecimal("95000"), "COP", Instant.now(), "ana.perez@mail.com", companyId, Instant.now());
            b.asignarViaje(new Booking.Viaje("Bogotá", "Medellín", salida, "Expreso Andino"));
            b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, Instant.now());
            b.cambiarEstado(BookingStatus.PAGO_APROBADO, Instant.now());
            b.cambiarEstado(BookingStatus.CONFIRMADA, Instant.now());
            bookings.save(b);
            passengers.save(new Passenger(b.getId(), "Ana Pérez", "CC", "1020304050", "12"));
            return ticketService.issue(b);
        });
    }

    private Ticket comprada(Instant salida) {
        return comprada(userId, UUID.randomUUID(), salida, null);
    }

    private RequestPostProcessor operador(UUID id, UUID empresa) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("role", "OPERADOR").claim("companyId", empresa.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    private RequestPostProcessor operador() {
        return operador(operadorId, companyId);
    }

    private RequestPostProcessor pasajero(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("role", "PASAJERO").claim("email", "ana.perez@mail.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor conductor() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "CONDUCTOR"))
                .authorities(new SimpleGrantedAuthority("ROLE_CONDUCTOR"));
    }

    private static String venta(UUID itineraryId, String email, String... asientos) {
        StringBuilder pasajeros = new StringBuilder();
        for (int i = 0; i < asientos.length; i++) {
            pasajeros.append(i == 0 ? "" : ",").append("{\"nombre\":\"Pasajero ").append(i)
                    .append("\",\"tipoDocumento\":\"CC\",\"numeroDocumento\":\"100200").append(i).append("\"}");
        }
        String comprador = email == null ? "{\"nombre\":\"Juan Gómez\",\"telefono\":\"3001234567\"}"
                : "{\"nombre\":\"Juan Gómez\",\"email\":\"" + email + "\"}";
        return "{\"itineraryId\":\"" + itineraryId + "\",\"seatNumbers\":[\"" + String.join("\",\"", asientos)
                + "\"],\"pasajeros\":[" + pasajeros + "],\"comprador\":" + comprador + "}";
    }

    private ResultActions vender(RequestPostProcessor quien, String clave, String body) throws Exception {
        return mvc.perform(post("/box-office/sales").with(quien).header("Idempotency-Key", clave)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private List<String> pasos(UUID bookingId) {
        return jdbc.queryForList("select paso from saga_log where booking_id = ? order by id", String.class, bookingId);
    }

    private List<JsonNode> eventos(String tipo) throws Exception {
        List<JsonNode> encontrados = new ArrayList<>();
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range("tripbus:events:test", Range.unbounded());
        for (MapRecord<String, Object, Object> r : records == null ? List.<MapRecord<String, Object, Object>>of()
                : records) {
            if (tipo.equals(r.getValue().get("type"))) {
                encontrados.add(objectMapper.readTree(String.valueOf(r.getValue().get("payload"))));
            }
        }
        return encontrados;
    }

    private long correosPara(String email) throws Exception {
        long n = 0;
        for (MimeMessage m : smtp.getReceivedMessages()) {
            if (Arrays.stream(m.getRecipients(Message.RecipientType.TO)).anyMatch(a -> a.toString().contains(email))) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ US-144: taquilla

    @Test
    void ventaEnTaquillaConCuentaQuedaANombreDelPasajeroYLeLlegaElTiquete() throws Exception {
        UUID itinerario = viaje(companyId, Duration.ofDays(1), "PROGRAMADO");
        retenciones(itinerario);
        UUID pasajeroRegistrado = UUID.randomUUID();
        when(identity.passengerIdByEmail(COMPRADOR)).thenReturn(Optional.of(pasajeroRegistrado));

        JsonNode venta = json(vender(operador(), "taquilla-cuenta-01", venta(itinerario, "Juan.Gomez@Mail.com", "7", "8"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"))
                .andExpect(jsonPath("$.compradorRegistrado").value(true))
                .andExpect(jsonPath("$.metodoPago").value("EFECTIVO"))
                .andExpect(jsonPath("$.total").value(190000)));

        UUID bookingId = UUID.fromString(venta.get("bookingId").asText());
        assertThat(venta.get("userId").asText()).isEqualTo(pasajeroRegistrado.toString());
        assertThat(venta.get("vendidoPor").asText()).isEqualTo(operadorId.toString());
        assertThat(venta.get("ticketId").isNull()).isFalse();
        verify(seatInventory).createHold(itinerario, List.of("7", "8"), operadorId); // retenido a nombre del operador
        verify(gateway, never()).charge(any(), any(), anyString(), anyString());
        assertThat(pasos(bookingId)).containsSubsequence("RESERVA_CREADA", "PAGO_EFECTIVO_APROBADO", "ASIENTO_VENDIDO",
                "TIQUETE_EMITIDO");
        assertThat(jdbc.queryForMap("select metodo, estado from payments where booking_id = ?", bookingId))
                .containsEntry("metodo", "EFECTIVO").containsEntry("estado", "APROBADO");
        assertThat(jdbc.queryForMap("select canal, vendido_por, comprador_nombre, comprador_email, origen "
                + "from bookings where id = ?", bookingId))
                .containsEntry("canal", "TAQUILLA").containsEntry("vendido_por", operadorId)
                .containsEntry("comprador_nombre", "Juan Gómez").containsEntry("comprador_email", COMPRADOR)
                .containsEntry("origen", "Bogotá");
        await().atMost(Duration.ofSeconds(10)).until(() -> correosPara(COMPRADOR) == 1);

        // Aparece en los viajes del pasajero y se valida al abordar igual que uno de la app.
        mvc.perform(get("/bookings").param("scope", "proximos").with(pasajero(pasajeroRegistrado)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.items[0].canal").value("TAQUILLA"));
        String qr = tickets.findByBookingId(bookingId).orElseThrow().getQrCode();
        mvc.perform(post("/tickets/validate").with(conductor()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("qrCode", qr))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valido").value(true));
    }

    @Test
    void ventaSinCuentaQuedaANombreDelOperadorYNoSeEnviaCorreo() throws Exception {
        UUID itinerario = viaje(companyId, Duration.ofHours(5), "PROGRAMADO");
        retenciones(itinerario);
        long correosAntes = smtp.getReceivedMessages().length;

        JsonNode venta = json(vender(operador(), "taquilla-sin-cuenta", venta(itinerario, null, "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.compradorRegistrado").value(false)));

        assertThat(venta.get("userId").asText()).isEqualTo(operadorId.toString());
        verify(identity, never()).passengerIdByEmail(anyString());
        assertThat(jdbc.queryForObject("select comprador_telefono from bookings where id = ?", String.class,
                UUID.fromString(venta.get("bookingId").asText()))).isEqualTo("3001234567");
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                .until(() -> smtp.getReceivedMessages().length == correosAntes);
    }

    @Test
    void elViajeDebeSerDeSuEmpresaNoCanceladoYSinSalir() throws Exception {
        UUID ajeno = viaje(UUID.randomUUID(), Duration.ofDays(1), "PROGRAMADO");
        UUID cancelado = viaje(companyId, Duration.ofDays(1), "CANCELADO");
        UUID salio = viaje(companyId, Duration.ofMinutes(-5), "PROGRAMADO");

        vender(operador(), "taquilla-ajena-001", venta(ajeno, null, "1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_NOT_FOUND"));
        vender(operador(), "taquilla-cancelada", venta(cancelado, null, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_CANCELLED"));
        vender(operador(), "taquilla-ya-salio1", venta(salio, null, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_DEPARTED"));
        vender(pasajero(userId), "taquilla-pasajero", venta(ajeno, null, "1")).andExpect(status().isForbidden());
        verify(seatInventory, never()).createHold(any(), anyList(), any());
    }

    @Test
    void laMismaIdempotencyKeyDevuelveLaMismaVentaSinVenderDosVeces() throws Exception {
        UUID itinerario = viaje(companyId, Duration.ofDays(1), "PROGRAMADO");
        retenciones(itinerario);
        String body = venta(itinerario, null, "15");

        String primera = json(vender(operador(), "taquilla-repetida", body).andExpect(status().isCreated()))
                .get("bookingId").asText();
        String segunda = json(vender(operador(), "taquilla-repetida", body).andExpect(status().isCreated()))
                .get("bookingId").asText();

        assertThat(segunda).isEqualTo(primera);
        verify(seatInventory, times(1)).createHold(any(), anyList(), any());
        vender(operador(), "taquilla-repetida", venta(itinerario, null, "16"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from payments where idempotency_key like ?", Long.class,
                "taquilla:" + operadorId + ":%")).isEqualTo(1);
    }

    @Test
    void cierreDeCajaDelOperadorDeOtroVendedorDeSuEmpresaYDelAdmin() throws Exception {
        UUID itinerario = viaje(companyId, Duration.ofDays(1), "PROGRAMADO");
        retenciones(itinerario);
        UUID otroVendedor = UUID.randomUUID();
        vender(operador(), "cierre-venta-0001", venta(itinerario, null, "1", "2")).andExpect(status().isCreated());
        vender(operador(), "cierre-venta-0002", venta(itinerario, null, "3")).andExpect(status().isCreated());
        vender(operador(otroVendedor, companyId), "cierre-venta-0003", venta(itinerario, null, "4"))
                .andExpect(status().isCreated());

        mvc.perform(get("/box-office/sales").with(operador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendedor").value(operadorId.toString()))
                .andExpect(jsonPath("$.cantidadVentas").value(2))
                .andExpect(jsonPath("$.cantidadTiquetes").value(2))
                .andExpect(jsonPath("$.cantidadAsientos").value(3))
                .andExpect(jsonPath("$.totalEfectivo").value(285000))
                .andExpect(jsonPath("$.ventas[0].compradorNombre").value("Juan Gómez"))
                .andExpect(jsonPath("$.ventas[0].estadoPago").value("APROBADO"));
        mvc.perform(get("/box-office/sales").param("vendedor", otroVendedor.toString()).with(operador()))
                .andExpect(jsonPath("$.cantidadVentas").value(1))
                .andExpect(jsonPath("$.totalEfectivo").value(95000));
        // Un operador de otra empresa no ve las ventas de esta.
        mvc.perform(get("/box-office/sales").param("vendedor", operadorId.toString())
                        .with(operador(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(jsonPath("$.cantidadVentas").value(0));
        mvc.perform(get("/box-office/sales").param("vendedor", operadorId.toString()).with(admin()))
                .andExpect(jsonPath("$.cantidadVentas").value(2));
        mvc.perform(get("/box-office/sales").with(admin()))
                .andExpect(jsonPath("$.vendedor").isEmpty());
        mvc.perform(get("/box-office/sales").param("fecha", "2020-01-01").with(operador()))
                .andExpect(jsonPath("$.fecha").value("2020-01-01"))
                .andExpect(jsonPath("$.cantidadVentas").value(0));
        mvc.perform(get("/box-office/sales").with(pasajero(userId))).andExpect(status().isForbidden());
        mvc.perform(get("/box-office/sales").param("fecha", "ayer").with(operador())).andExpect(status().isBadRequest());
    }

    @Test
    void elEfectivoSeDevuelveEnTaquillaSinPasarPorLaPasarela() throws Exception {
        UUID itinerario = viaje(companyId, Duration.ofDays(1), "PROGRAMADO");
        retenciones(itinerario);

        // El asiento se perdió al confirmar: la venta queda reembolsada (el operador devuelve el efectivo).
        when(seatInventory.confirmSale(any(), any())).thenThrow(ApiException.conflict("SEAT_UNAVAILABLE", "ocupado"));
        JsonNode perdida = json(vender(operador(), "taquilla-perdida", venta(itinerario, null, "30"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("REEMBOLSADA"))
                .andExpect(jsonPath("$.ticketId").isEmpty()));
        assertThat(jdbc.queryForObject("select count(*) from refunds r join payments p on p.id = r.payment_id "
                + "where p.booking_id = ?", Long.class, UUID.fromString(perdida.get("bookingId").asText()))).isEqualTo(1);

        // La empresa cancela el viaje de una venta confirmada y el dueño (el operador, sin cuenta) pide el reembolso.
        reset(seatInventory);
        retenciones(itinerario);
        when(seatInventory.confirmSale(any(), any())).thenAnswer(inv -> new SeatInventoryClient.Hold(inv.getArgument(0),
                null, List.of(), null, null, null, "CONFIRMADA", Instant.now(), 0));
        String confirmada = json(vender(operador(), "taquilla-cancelada-2", venta(itinerario, null, "31"))
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"))).get("bookingId").asText();
        affectedService.onItineraryCancelled(itinerario);
        mvc.perform(post("/bookings/{id}/affected/refund", confirmada).with(operador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("REEMBOLSADA"));

        verify(gateway, never()).refund(any(), any());
        mvc.perform(get("/box-office/sales").with(operador()))
                .andExpect(jsonPath("$.totalEfectivo").value(0))
                .andExpect(jsonPath("$.totalReembolsado").value(190000));
    }

    // ------------------------------------------------------------------ US-145: mis viajes

    @Test
    void misViajesProximosYPasadosOrdenadosYConSusOpciones() throws Exception {
        Instant ahora = Instant.now();
        Instant inicioDeHoy = ahora.atZone(BOGOTA).toLocalDate().atStartOfDay(BOGOTA)
                .toInstant();
        Ticket lejano = comprada(ahora.plus(Duration.ofDays(3)));
        Ticket manana = comprada(ahora.plus(Duration.ofHours(20)));
        Ticket salioHoy = comprada(inicioDeHoy.plus(Duration.between(inicioDeHoy, ahora).dividedBy(2)));
        Ticket viejo = comprada(ahora.minus(Duration.ofDays(10)));
        Ticket antiguo = comprada(ahora.minus(Duration.ofDays(40)));
        jdbc.update("update tickets set estado = 'USADO' where id = ?", viejo.getId());
        jdbc.update("update bookings set estado = 'REEMBOLSADA' where id = ?", antiguo.getBookingId());
        jdbc.update("update tickets set estado = 'ANULADO' where id = ?", antiguo.getId());
        // No aparecen: una reserva sin pagar y la de otro pasajero.
        tx.executeWithoutResult(s -> bookings.save(new Booking(UUID.randomUUID(), userId, UUID.randomUUID(), null,
                List.of("1"), BigDecimal.TEN, "COP", ahora, null, companyId, ahora)));
        comprada(UUID.randomUUID(), UUID.randomUUID(), ahora.plus(Duration.ofDays(1)), null);

        mvc.perform(get("/bookings").param("scope", "proximos").with(pasajero(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].bookingId").value(salioHoy.getBookingId().toString()))
                .andExpect(jsonPath("$.items[0].puedeCambiarAsiento").value(false))
                .andExpect(jsonPath("$.items[1].bookingId").value(manana.getBookingId().toString()))
                .andExpect(jsonPath("$.items[1].puedeReprogramar").value(true))
                .andExpect(jsonPath("$.items[1].puedeCambiarAsiento").value(true))
                .andExpect(jsonPath("$.items[1].origen").value("Bogotá"))
                .andExpect(jsonPath("$.items[1].empresa").value("Expreso Andino"))
                .andExpect(jsonPath("$.items[1].estadoTiquete").value("EMITIDO"))
                .andExpect(jsonPath("$.items[2].bookingId").value(lejano.getBookingId().toString()));
        mvc.perform(get("/bookings").param("scope", "pasados").with(pasajero(userId)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].bookingId").value(viejo.getBookingId().toString()))
                .andExpect(jsonPath("$.items[0].estadoTiquete").value("USADO"))
                .andExpect(jsonPath("$.items[0].puedeReprogramar").value(false))
                .andExpect(jsonPath("$.items[1].estado").value("REEMBOLSADA"));
        mvc.perform(get("/bookings").param("scope", "PROXIMOS").param("page", "1").param("size", "2")
                        .with(pasajero(userId)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].bookingId").value(lejano.getBookingId().toString()));
        mvc.perform(get("/bookings").param("scope", "todos").with(pasajero(userId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_SCOPE"));
        mvc.perform(get("/bookings").param("scope", "pasados").param("size", "500").with(pasajero(userId)))
                .andExpect(status().isBadRequest());
        // Sin scope sigue respondiendo la lista de siempre.
        mvc.perform(get("/bookings").with(pasajero(userId))).andExpect(jsonPath("$.length()").value(6));
    }

    @Test
    void reenvioDelTiqueteHastaTresVecesPorDia() throws Exception {
        Ticket t = comprada(Instant.now().plus(Duration.ofDays(2)));
        long antes = correosPara("ana.perez@mail.com");

        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/tickets/{id}/resend", t.getId()).with(pasajero(userId)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.reenviosHoy").value(i))
                    .andExpect(jsonPath("$.enviadoA").value("a***@mail.com"));
        }
        mvc.perform(post("/tickets/{id}/resend", t.getId()).with(pasajero(userId)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RESEND_LIMIT_REACHED"));
        await().atMost(Duration.ofSeconds(10)).until(() -> correosPara("ana.perez@mail.com") == antes + 3);
        mvc.perform(post("/tickets/{id}/resend", t.getId()).with(pasajero(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        jdbc.update("update tickets set estado = 'REPROGRAMADO' where id = ?", t.getId());
        mvc.perform(post("/tickets/{id}/resend", t.getId()).with(pasajero(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_NOT_RESENDABLE"));
    }

    @Test
    void elPdfDelTiqueteTieneElNumeroYSoloLoVenElDuenoLaEmpresaYElAdmin() throws Exception {
        Ticket t = comprada(Instant.now().plus(Duration.ofDays(2)));

        byte[] pdf = mvc.perform(get("/tickets/{id}/pdf", t.getId()).with(pasajero(userId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"tiquete-" + t.getId() + ".pdf\""))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String texto = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertThat(texto).contains(t.getId().toString(), "Bogotá", "Medellín", "Ana Pérez", "12", "Expreso Andino");
        mvc.perform(get("/tickets/{id}/pdf", t.getId()).with(operador())).andExpect(status().isOk());
        mvc.perform(get("/tickets/{id}/pdf", t.getId()).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/tickets/{id}/pdf", t.getId()).with(operador(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/tickets/{id}/pdf", t.getId()).with(pasajero(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ US-153: no-show

    @Test
    void elJobPasaANoShowLosTiquetesDeViajesQueSalieronHaceMasDeMediaHora() throws Exception {
        Ticket noAbordo = comprada(Instant.now().minus(Duration.ofMinutes(40)));
        Ticket recien = comprada(Instant.now().minus(Duration.ofMinutes(10)));
        Ticket abordo = comprada(Instant.now().minus(Duration.ofHours(2)));
        jdbc.update("update tickets set estado = 'USADO' where id = ?", abordo.getId());
        // Afectada por una cancelación de la empresa: no es un no-show.
        UUID cancelado = viaje(companyId, Duration.ofHours(-2), "PROGRAMADO");
        Ticket afectada = comprada(userId, cancelado, Instant.now().minus(Duration.ofHours(2)), null);
        affectedService.onItineraryCancelled(cancelado);

        noShowJob.runNow();

        assertThat(tickets.findById(noAbordo.getId()).orElseThrow().getEstado()).isEqualTo("NO_SHOW");
        assertThat(tickets.findById(recien.getId()).orElseThrow().getEstado()).isEqualTo("EMITIDO");
        assertThat(tickets.findById(abordo.getId()).orElseThrow().getEstado()).isEqualTo("USADO");
        assertThat(tickets.findById(afectada.getId()).orElseThrow().getEstado()).isEqualTo("EMITIDO");
        assertThat(bookings.findById(noAbordo.getBookingId()).orElseThrow().getEstado())
                .isEqualTo(BookingStatus.CONFIRMADA); // sin reembolso
        assertThat(jdbc.queryForObject("select count(*) from refunds r join payments p on p.id = r.payment_id "
                + "where p.booking_id = ?", Long.class, noAbordo.getBookingId())).isZero();
        assertThat(eventos("TicketNoShow")).anySatisfy(e -> {
            assertThat(e.get("ticketId").asText()).isEqualTo(noAbordo.getId().toString());
            assertThat(e.get("bookingId").asText()).isEqualTo(noAbordo.getBookingId().toString());
            assertThat(e.get("userId").asText()).isEqualTo(userId.toString());
            assertThat(e.hasNonNull("itineraryId")).isTrue();
        });
        assertThat(pasos(noAbordo.getBookingId())).contains("NO_SHOW");
        mvc.perform(post("/tickets/validate").with(conductor()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("qrCode", noAbordo.getQrCode()))))
                .andExpect(jsonPath("$.valido").value(false))
                .andExpect(jsonPath("$.motivo").value("NO_SHOW"));
        assertThat(noShowJob.runNow()).isZero(); // idempotente
    }

    /** Reserva en NO_SHOW cuyo viaje salió hace {@code hace}, y un viaje nuevo de la misma ruta y empresa. */
    private Ticket noShow(Duration hace, UUID offerId) {
        UUID original = viaje(companyId, hace.negated(), "PROGRAMADO");
        Ticket t = comprada(userId, original, Instant.now().minus(hace), offerId);
        jdbc.update("update tickets set estado = 'NO_SHOW' where id = ?", t.getId());
        return t;
    }

    private ResultActions reprogramar(UUID bookingId, UUID holdId) throws Exception {
        return mvc.perform(post("/bookings/{id}/reschedule", bookingId).with(pasajero(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"holdId\":\"" + holdId + "\"}"));
    }

    @Test
    void unNoShowSeReprogramaDentroDeLaVentanaConElCargoYFueraNo() throws Exception {
        Ticket dentro = noShow(Duration.ofHours(3), null);
        UUID nuevo = viaje(companyId, Duration.ofDays(2), "PROGRAMADO");
        when(catalog.price(nuevo)).thenReturn(new CatalogClient.Price(nuevo, new BigDecimal("100000"), "COP", "PROGRAMADO"));

        reprogramar(dentro.getBookingId(), retencion(nuevo, userId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("PENDIENTE_PAGO"))
                // 100000 − 95000 de diferencia + 20 % de 95000.
                .andExpect(jsonPath("$.total").value(24000));
        // Salió hace 3 h: si fue hoy (hora de Colombia) sigue en próximos; si no, en pasados.
        Instant salida = bookings.findById(dentro.getBookingId()).orElseThrow().getFechaSalida();
        boolean deHoy = salida.atZone(BOGOTA).toLocalDate().equals(Instant.now().atZone(BOGOTA).toLocalDate());
        mvc.perform(get("/bookings").param("scope", deHoy ? "proximos" : "pasados").with(pasajero(userId)))
                .andExpect(jsonPath("$.items[0].bookingId").value(dentro.getBookingId().toString()))
                .andExpect(jsonPath("$.items[0].estadoTiquete").value("NO_SHOW"))
                .andExpect(jsonPath("$.items[0].puedeReprogramar").value(false)); // ya tiene una reprogramación viva

        Ticket fuera = noShow(Duration.ofHours(25), null);
        reprogramar(fuera.getBookingId(), retencion(nuevo, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NO_SHOW_WINDOW_CLOSED"));
    }

    // ------------------------------------------------------------------ US-166: tarifa de oferta

    @Test
    void unaTarifaDeOfertaQueNoPermiteReprogramarEs409YSinFlashOffers503() throws Exception {
        UUID offerId = UUID.randomUUID();
        UUID original = viaje(companyId, Duration.ofDays(2), "PROGRAMADO");
        Ticket t = comprada(userId, original, Instant.now().plus(Duration.ofDays(2)), offerId);
        UUID nuevo = viaje(companyId, Duration.ofDays(4), "PROGRAMADO");
        when(flashOffers.offer(offerId)).thenReturn(new FlashOffersClient.OfferDetails(offerId, original, companyId,
                new BigDecimal("60000"), new BigDecimal("95000"), false));

        reprogramar(t.getBookingId(), retencion(nuevo, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OFFER_FARE_NOT_RESCHEDULABLE"));

        when(flashOffers.offer(offerId)).thenThrow(ApiException.unavailable("OFFERS_UNAVAILABLE", "caído"));
        reprogramar(t.getBookingId(), retencion(nuevo, userId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("OFFERS_UNAVAILABLE"));

        // Un no-show de oferta tampoco se puede, sin consultar flash-offers.
        Ticket noShowDeOferta = noShow(Duration.ofHours(1), UUID.randomUUID());
        reprogramar(noShowDeOferta.getBookingId(), retencion(nuevo, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OFFER_FARE_NOT_RESCHEDULABLE"));
        assertThat(bookings.findByUserIdOrderByCreadoEnDesc(userId)).hasSize(2); // no se creó ninguna reprogramación
    }

    @Test
    void unaReservaDeOfertaAfectadaPorLaEmpresaSiSePuedeReprogramarGratis() throws Exception {
        UUID offerId = UUID.randomUUID();
        UUID cancelado = viaje(companyId, Duration.ofDays(1), "PROGRAMADO");
        Ticket t = comprada(userId, cancelado, Instant.now().plus(Duration.ofDays(1)), offerId);
        when(flashOffers.offer(offerId)).thenReturn(new FlashOffersClient.OfferDetails(offerId, cancelado, companyId,
                new BigDecimal("60000"), new BigDecimal("95000"), false));
        affectedService.onItineraryCancelled(cancelado);
        UUID nuevo = viaje(UUID.randomUUID(), Duration.ofDays(2), "PROGRAMADO");

        mvc.perform(post("/bookings/{id}/affected/reschedule", t.getBookingId()).with(pasajero(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"" + retencion(nuevo, userId) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("REPROGRAMADA"));

        verify(flashOffers, never()).offer(any());
        assertThat(tickets.findById(t.getId()).orElseThrow().getEstado()).isEqualTo("REPROGRAMADO");
    }

    // ------------------------------------------------------------------ datos del viaje en la reserva

    @Test
    void laReservaGuardaElViajeElRellenoEsIdempotenteYUnaReprogramacionDeCatalogMueveLaSalida() {
        UUID conCatalog = viaje(companyId, Duration.ofDays(3), "PROGRAMADO");
        UUID sinCatalog = UUID.randomUUID();
        UUID[] ids = tx.execute(s -> {
            Booking a = bookings.save(new Booking(UUID.randomUUID(), userId, conCatalog, null, List.of("1"),
                    BigDecimal.TEN, "COP", Instant.now(), Instant.now()));
            Booking b = bookings.save(new Booking(UUID.randomUUID(), userId, sinCatalog, null, List.of("1"),
                    BigDecimal.TEN, "COP", Instant.now(), Instant.now()));
            return new UUID[] {a.getId(), b.getId()};
        });

        assertThat(backfill.run()).isGreaterThanOrEqualTo(1);
        assertThat(backfill.run()).isZero();

        Booking rellenada = bookings.findById(ids[0]).orElseThrow();
        assertThat(rellenada.getOrigen()).isEqualTo("Bogotá");
        assertThat(rellenada.getEmpresaNombre()).isEqualTo("Expreso Andino");
        assertThat(rellenada.getFechaSalida()).isNotNull();
        assertThat(bookings.findById(ids[1]).orElseThrow().getFechaSalida()).isNull(); // se reintenta otro arranque

        Instant nuevaSalida = Instant.parse("2030-01-02T15:00:00Z");
        assertThat(bookingService.onItineraryRescheduled(conCatalog, nuevaSalida)).isEqualTo(1);
        assertThat(bookings.findById(ids[0]).orElseThrow().getFechaSalida()).isEqualTo(nuevaSalida);
        assertThat(bookingService.onItineraryRescheduled(conCatalog, null)).isZero();
    }
}
