package com.tripbus.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.RemoteErrors;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.payment.WebhookSigner;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.service.SagaRecoveryJob;
import com.tripbus.booking.service.TicketService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 3: reprogramar el tiquete (US-150), cambiar de asiento (US-139) y corregir datos del pasajero (US-152),
 * contra PostgreSQL 16 y Redis 7 reales (Testcontainers) con las migraciones Flyway reales. Seat Inventory, Catalog,
 * Flash Offers y la pasarela se simulan con mocks, como en BookingSagaIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class RescheduleIT {

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
    private BookingRepository bookings;
    @Autowired
    private PassengerRepository passengers;
    @Autowired
    private TicketRepository tickets;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private SagaRecoveryJob recoveryJob;
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
    private PaymentGateway gateway;

    private final UUID companyId = UUID.randomUUID();
    private UUID userId;
    private UUID viajeOriginal;

    @BeforeEach
    void simularServicios() {
        reset(seatInventory, catalog, gateway);
        userId = UUID.randomUUID();
        viajeOriginal = viaje("Bogotá", "Medellín", companyId, Duration.ofDays(2), "95000");
        when(gateway.charge(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> "sbx_" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** Itinerario en catalog que sale dentro de {@code enCuanto}. */
    private UUID viaje(String origen, String destino, UUID empresa, Duration enCuanto, String precio) {
        UUID id = UUID.randomUUID();
        when(catalog.itinerary(id)).thenReturn(new CatalogClient.ItinerarySummary(id, origen, destino,
                Instant.now().plus(enCuanto), null, "Expreso Andino", "ABC123", empresa, "PROGRAMADO"));
        when(catalog.price(id)).thenReturn(new CatalogClient.Price(id, new BigDecimal(precio), "COP", "PROGRAMADO"));
        return id;
    }

    private UUID viajeNuevo(String precio) {
        return viaje("Bogotá", "Medellín", companyId, Duration.ofDays(5), precio);
    }

    /** Reserva CONFIRMADA de 95.000 con su tiquete EMITIDO en {@link #viajeOriginal}, asiento 12. */
    private Ticket comprada() {
        return comprada(viajeOriginal);
    }

    private Ticket comprada(UUID itineraryId) {
        return tx.execute(s -> {
            Booking b = new Booking(UUID.randomUUID(), userId, itineraryId, null, List.of("12"),
                    new BigDecimal("95000"), "COP", Instant.now(), "ana.perez@mail.com", companyId, Instant.now());
            b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, Instant.now());
            b.cambiarEstado(BookingStatus.PAGO_APROBADO, Instant.now());
            b.cambiarEstado(BookingStatus.CONFIRMADA, Instant.now());
            bookings.save(b);
            passengers.save(new Passenger(b.getId(), "Ana Pérez", "CC", "1020304050", "12"));
            return ticketService.issue(b);
        });
    }

    /** Retención activa de {@code dueno} en {@code itineraryId}. */
    private UUID retencion(UUID itineraryId, UUID dueno, String... asientos) {
        UUID holdId = UUID.randomUUID();
        when(seatInventory.getHold(holdId)).thenReturn(new SeatInventoryClient.Hold(holdId, itineraryId,
                List.of(asientos), dueno, null, null, "ACTIVA", Instant.now().plusSeconds(600), 600));
        when(seatInventory.confirmSale(eq(holdId), any())).thenReturn(new SeatInventoryClient.Hold(holdId,
                itineraryId, List.of(asientos), dueno, null, null, "CONFIRMADA", Instant.now(), 0));
        return holdId;
    }

    private RequestPostProcessor pasajero() {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("role", "PASAJERO")
                        .claim("email", "ana.perez@mail.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"));
    }

    private RequestPostProcessor operador() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "OPERADOR")
                        .claim("companyId", companyId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    private RequestPostProcessor conductor() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "CONDUCTOR"))
                .authorities(new SimpleGrantedAuthority("ROLE_CONDUCTOR"));
    }

    private ResultActions reprogramar(UUID bookingId, UUID holdId) throws Exception {
        return mvc.perform(post("/bookings/{id}/reschedule", bookingId).with(pasajero())
                .contentType(MediaType.APPLICATION_JSON).content("{\"holdId\":\"" + holdId + "\"}"));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions validar(String qrCode) throws Exception {
        return mvc.perform(post("/tickets/validate").with(conductor()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("qrCode", qrCode))));
    }

    /** Paga la reserva y la pasarela aprueba el monto indicado (webhook firmado). */
    private void pagarYAprobar(UUID bookingId, int monto) throws Exception {
        String pago = mvc.perform(post("/bookings/{id}/pay", bookingId).with(pasajero())
                        .header("Idempotency-Key", "pago-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardToken\":\"tok_visa_approved\",\"cardLast4\":\"4242\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.monto").value(monto))
                .andReturn().getResponse().getContentAsString();
        webhook(objectMapper.readTree(pago).get("paymentId").asText(), "APPROVED", monto);
    }

    private void webhook(String paymentId, String estado, int monto) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "eventId", "evt_" + UUID.randomUUID(), "reference", paymentId, "status", estado,
                "amount", monto, "currency", "COP", "timestamp", Instant.now().toString()));
        mvc.perform(post("/webhooks/payment").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signer.sign(body)).content(body))
                .andExpect(status().isOk());
    }

    private Booking releer(UUID bookingId) {
        return bookings.findById(bookingId).orElseThrow();
    }

    private Ticket releerTiquete(UUID ticketId) {
        return tickets.findById(ticketId).orElseThrow();
    }

    /** Tiquetes que aún sirven para abordar (EMITIDO) de las reservas del usuario de la prueba. */
    private long tiquetesValidosDelUsuario() {
        return jdbc.queryForObject("select count(*) from tickets t join bookings b on b.id = t.booking_id "
                + "where b.user_id = ? and t.estado = 'EMITIDO'", Long.class, userId);
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

    // ------------------------------------------------------------------ US-150

    @Test
    void reprogramarConDiferenciaSePagaYAlConfirmarseReemplazaElTiquete() throws Exception {
        Ticket viejo = comprada();
        UUID nuevoViaje = viajeNuevo("120000");
        UUID holdId = retencion(nuevoViaje, userId, "20");

        JsonNode nueva = json(reprogramar(viejo.getBookingId(), holdId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("PENDIENTE_PAGO"))
                .andExpect(jsonPath("$.total").value(25000))
                .andExpect(jsonPath("$.origenBookingId").value(viejo.getBookingId().toString()))
                .andExpect(jsonPath("$.seatNumbers[0]").value("20"))
                .andExpect(jsonPath("$.pasajeros[0].nombre").value("Ana Pérez"))
                .andExpect(jsonPath("$.pasajeros[0].asiento").value("20")));
        UUID nuevaId = UUID.fromString(nueva.get("bookingId").asText());
        // Mientras no se pague, la original sigue intacta.
        assertThat(releer(viejo.getBookingId()).getEstado()).isEqualTo(BookingStatus.CONFIRMADA);
        assertThat(releerTiquete(viejo.getId()).getEstado()).isEqualTo("EMITIDO");
        // Reintentar con la misma retención devuelve la misma reserva nueva.
        reprogramar(viejo.getBookingId(), holdId).andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(nuevaId.toString()));

        pagarYAprobar(nuevaId, 25000);

        Ticket nuevo = tickets.findByBookingId(nuevaId).orElseThrow();
        assertThat(releer(nuevaId).getEstado()).isEqualTo(BookingStatus.CONFIRMADA);
        assertThat(releer(viejo.getBookingId()).getEstado()).isEqualTo(BookingStatus.REPROGRAMADA);
        assertThat(releerTiquete(viejo.getId()).getEstado()).isEqualTo("REPROGRAMADO");
        assertThat(nuevo.getEstado()).isEqualTo("EMITIDO");
        assertThat(nuevo.getQrCode()).isNotEqualTo(viejo.getQrCode()).contains("|20");
        verify(seatInventory).confirmSale(holdId, nuevaId);
        verify(seatInventory).voidSale(viajeOriginal, viejo.getBookingId(), List.of("12"), "REPROGRAMACION");
        assertThat(releer(viejo.getBookingId()).getAnulacionResueltaEn()).isNotNull();

        mvc.perform(get("/bookings/{id}/saga", nuevaId).with(pasajero()))
                .andExpect(jsonPath("$[?(@.paso == 'REPROGRAMACION_APLICADA')]").exists());
        mvc.perform(get("/bookings/{id}/saga", viejo.getBookingId()).with(pasajero()))
                .andExpect(jsonPath("$[?(@.paso == 'ANULAR_ASIENTOS_ANTERIORES' && @.estado == 'OK')]").exists());
        // El OPERADOR de la empresa consulta las dos reservas como siempre.
        mvc.perform(get("/bookings/{id}", nuevaId).with(operador())).andExpect(status().isOk());

        JsonNode evento = eventos("TicketRescheduled").stream()
                .filter(e -> e.get("bookingIdNuevo").asText().equals(nuevaId.toString())).findFirst().orElseThrow();
        assertThat(evento.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(evento.get("bookingIdAnterior").asText()).isEqualTo(viejo.getBookingId().toString());
        assertThat(evento.get("ticketIdAnterior").asText()).isEqualTo(viejo.getId().toString());
        assertThat(evento.get("ticketIdNuevo").asText()).isEqualTo(nuevo.getId().toString());
        assertThat(evento.get("itineraryIdAnterior").asText()).isEqualTo(viajeOriginal.toString());
        assertThat(evento.get("itineraryIdNuevo").asText()).isEqualTo(nuevoViaje.toString());
    }

    @Test
    void reprogramarSinDiferenciaSeConfirmaDeInmediatoYNoHayReembolso() throws Exception {
        Ticket viejo = comprada();
        UUID holdId = retencion(viajeNuevo("80000"), userId, "5");

        JsonNode nueva = json(reprogramar(viejo.getBookingId(), holdId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.ticketId").isNotEmpty())
                .andExpect(jsonPath("$.pago").isEmpty()));

        verify(gateway, never()).charge(any(), any(), anyString(), anyString());
        verify(gateway, never()).refund(any(), any());
        assertThat(releerTiquete(viejo.getId()).getEstado()).isEqualTo("REPROGRAMADO");
        // El pasaje ya pagado (95.000) se conserva en la cadena aunque el viaje nuevo cueste menos.
        assertThat(releer(UUID.fromString(nueva.get("bookingId").asText())).getValorPagado())
                .isEqualByComparingTo("95000");
    }

    @Test
    void conRetencionEquivocadaResponde409YNoCreaNada() throws Exception {
        Ticket viejo = comprada();
        long antes = bookings.count();

        reprogramar(viejo.getBookingId(), retencion(viajeNuevo("95000"), UUID.randomUUID(), "20"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("HOLD_NOT_OWNED"));
        UUID otraRuta = viaje("Bogotá", "Cali", companyId, Duration.ofDays(3), "95000");
        reprogramar(viejo.getBookingId(), retencion(otraRuta, userId, "20"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROUTE_MISMATCH"));
        UUID otraEmpresa = viaje("Bogotá", "Medellín", UUID.randomUUID(), Duration.ofDays(3), "95000");
        reprogramar(viejo.getBookingId(), retencion(otraEmpresa, userId, "20"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMPANY_MISMATCH"));
        reprogramar(viejo.getBookingId(), retencion(viajeNuevo("95000"), userId, "20", "21"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEATS_PASSENGERS_MISMATCH"));

        assertThat(bookings.count()).isEqualTo(antes);
        assertThat(releerTiquete(viejo.getId()).getEstado()).isEqualTo("EMITIDO");
    }

    @Test
    void fueraDePlazoNoSePuedeReprogramar() throws Exception {
        Ticket viejo = comprada(viaje("Bogotá", "Medellín", companyId, Duration.ofHours(2), "95000"));

        reprogramar(viejo.getBookingId(), retencion(viajeNuevo("95000"), userId, "20"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESCHEDULE_WINDOW_CLOSED"));
    }

    @Test
    void elLimiteDeDosReprogramacionesCuentaTodaLaCadena() throws Exception {
        Ticket viejo = comprada();
        UUID primera = UUID.fromString(json(reprogramar(viejo.getBookingId(),
                retencion(viajeNuevo("95000"), userId, "20")).andExpect(status().isCreated())).get("bookingId").asText());
        UUID segunda = UUID.fromString(json(reprogramar(primera,
                retencion(viajeNuevo("95000"), userId, "21")).andExpect(status().isCreated())).get("bookingId").asText());

        reprogramar(segunda, retencion(viajeNuevo("95000"), userId, "22"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESCHEDULE_LIMIT_REACHED"));
        // La reserva vieja ya no es CONFIRMADA: tampoco se puede volver a reprogramar.
        reprogramar(viejo.getBookingId(), retencion(viajeNuevo("95000"), userId, "23"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BOOKING_NOT_RESCHEDULABLE"));
        assertThat(tiquetesValidosDelUsuario()).isEqualTo(1);
    }

    @Test
    void siLaAnulacionFallaElJobDeRecuperacionLaReintenta() throws Exception {
        Ticket viejo = comprada();
        doThrow(new RemoteErrors.RemoteUnavailableException("seat-inventory caído", null))
                .when(seatInventory).voidSale(any(), eq(viejo.getBookingId()), any(), eq("REPROGRAMACION"));

        reprogramar(viejo.getBookingId(), retencion(viajeNuevo("95000"), userId, "20"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        // El tiquete viejo ya no sirve aunque sus asientos sigan sin anular.
        assertThat(releerTiquete(viejo.getId()).getEstado()).isEqualTo("REPROGRAMADO");
        assertThat(releer(viejo.getBookingId()).getAnulacionResueltaEn()).isNull();
        mvc.perform(get("/bookings/{id}/saga", viejo.getBookingId()).with(pasajero()))
                .andExpect(jsonPath("$[?(@.paso == 'ANULAR_ASIENTOS_ANTERIORES' && @.estado == 'REINTENTO')]")
                        .exists());

        // Seat-inventory se recupera; el job retoma las anulaciones pendientes de más de 15 s.
        doNothing().when(seatInventory).voidSale(any(), eq(viejo.getBookingId()), any(), eq("REPROGRAMACION"));
        jdbc.update("update bookings set actualizado_en = actualizado_en - interval '1 minute' where id = ?",
                viejo.getBookingId());
        recoveryJob.recoverNow();

        verify(seatInventory, times(2)).voidSale(viajeOriginal, viejo.getBookingId(), List.of("12"), "REPROGRAMACION");
        assertThat(releer(viejo.getBookingId()).getAnulacionResueltaEn()).isNotNull();
        // Ya resuelta: otra pasada no la repite.
        recoveryJob.recoverNow();
        verify(seatInventory, times(2)).voidSale(viajeOriginal, viejo.getBookingId(), List.of("12"), "REPROGRAMACION");
    }

    @Test
    void nuncaQuedanDosTiquetesValidos() throws Exception {
        Ticket viejo = comprada();
        reprogramar(viejo.getBookingId(), retencion(viajeNuevo("95000"), userId, "20"))
                .andExpect(status().isCreated());

        validar(viejo.getQrCode()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_RESCHEDULED"));
        assertThat(tiquetesValidosDelUsuario()).isEqualTo(1);
        Ticket nuevo = tickets.findAll().stream()
                .filter(t -> userId.equals(releer(t.getBookingId()).getUserId()) && "EMITIDO".equals(t.getEstado()))
                .findFirst().orElseThrow();
        validar(nuevo.getQrCode()).andExpect(status().isOk()).andExpect(jsonPath("$.valido").value(true));
    }

    @Test
    void siElTiqueteViejoSeUsaAntesDePagarLaReprogramacionSeReembolsa() throws Exception {
        Ticket viejo = comprada();
        UUID nuevaId = UUID.fromString(json(reprogramar(viejo.getBookingId(),
                retencion(viajeNuevo("120000"), userId, "20")).andExpect(status().isCreated())).get("bookingId").asText());

        // El pasajero aborda con el tiquete viejo mientras la reprogramación espera el pago.
        validar(viejo.getQrCode()).andExpect(jsonPath("$.valido").value(true));
        pagarYAprobar(nuevaId, 25000);

        assertThat(releer(nuevaId).getEstado()).isEqualTo(BookingStatus.REEMBOLSADA);
        assertThat(tickets.findByBookingId(nuevaId)).isEmpty();
        assertThat(releer(viejo.getBookingId()).getEstado()).isEqualTo(BookingStatus.CONFIRMADA);
        assertThat(releerTiquete(viejo.getId()).getEstado()).isEqualTo("USADO");
        verify(gateway).refund(anyString(), any());
        verify(seatInventory, never()).voidSale(any(), eq(viejo.getBookingId()), any(), any());
    }

    @Test
    void dosReprogramacionesSimultaneasSoloCreanUna() throws Exception {
        Ticket viejo = comprada();
        UUID hold1 = retencion(viajeNuevo("120000"), userId, "20");
        UUID hold2 = retencion(viajeNuevo("120000"), userId, "21");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Integer>> intentos = new ArrayList<>();
        for (UUID hold : List.of(hold1, hold2)) {
            intentos.add(pool.submit(() -> {
                salida.await();
                return reprogramar(viejo.getBookingId(), hold).andReturn().getResponse().getStatus();
            }));
        }
        salida.countDown();
        List<Integer> estados = new ArrayList<>();
        for (Future<Integer> f : intentos) {
            estados.add(f.get());
        }
        pool.shutdown();

        assertThat(estados).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from bookings where origen_booking_id = ?", Long.class,
                viejo.getBookingId())).isEqualTo(1);
    }

    @Test
    void siElPagoDeLaReprogramacionSeRechazaLaOriginalSigueIntacta() throws Exception {
        Ticket viejo = comprada();
        UUID nuevaId = UUID.fromString(json(reprogramar(viejo.getBookingId(),
                retencion(viajeNuevo("120000"), userId, "20")).andExpect(status().isCreated())).get("bookingId").asText());
        String pago = mvc.perform(post("/bookings/{id}/pay", nuevaId).with(pasajero())
                        .header("Idempotency-Key", "pago-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardToken\":\"tok_visa_declined\",\"cardLast4\":\"4242\"}"))
                .andReturn().getResponse().getContentAsString();
        webhook(objectMapper.readTree(pago).get("paymentId").asText(), "DECLINED", 25000);

        assertThat(releer(nuevaId).getEstado()).isEqualTo(BookingStatus.PAGO_RECHAZADO);
        assertThat(releer(viejo.getBookingId()).getEstado()).isEqualTo(BookingStatus.CONFIRMADA);
        assertThat(releerTiquete(viejo.getId()).getEstado()).isEqualTo("EMITIDO");
        // Se puede intentar de nuevo con otra retención.
        reprogramar(viejo.getBookingId(), retencion(viajeNuevo("95000"), userId, "21"))
                .andExpect(status().isCreated());
    }

    @Test
    void elOperadorConsultaPeroNoReprogramaNiCambiaElTiquete() throws Exception {
        Ticket viejo = comprada();
        mvc.perform(post("/bookings/{id}/reschedule", viejo.getBookingId()).with(operador())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"" + retencion(viajeNuevo("95000"), userId, "20") + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_BOOKING_OWNER"));
        mvc.perform(post("/tickets/{id}/seat", viejo.getId()).with(operador())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromSeat\":\"12\",\"toSeat\":\"14\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_TICKET_OWNER"));
        mvc.perform(get("/tickets/{id}", viejo.getId()).with(operador())).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ US-139

    @Test
    void cambiarDeAsientoGeneraUnQrNuevoYElViejoDejaDeValidar() throws Exception {
        Ticket ticket = comprada();

        mvc.perform(post("/tickets/{id}/seat", ticket.getId()).with(pasajero())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromSeat\":\"12\",\"toSeat\":\"14\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatNumbers[0]").value("14"))
                .andExpect(jsonPath("$.pasajeros[0].asiento").value("14"))
                .andExpect(jsonPath("$.estado").value("EMITIDO"));

        verify(seatInventory).swapSeat(viajeOriginal, ticket.getBookingId(), "12", "14");
        Ticket actualizado = releerTiquete(ticket.getId());
        assertThat(actualizado.getQrToken()).isNotEqualTo(ticket.getQrToken());
        assertThat(releer(ticket.getBookingId()).getSeatNumbers()).containsExactly("14");
        validar(ticket.getQrCode()).andExpect(status().isOk())
                .andExpect(jsonPath("$.valido").value(false))
                .andExpect(jsonPath("$.motivo").value("QR_REEMPLAZADO"));
        validar(actualizado.getQrCode()).andExpect(jsonPath("$.valido").value(true))
                .andExpect(jsonPath("$.asientos").value("14"));
        assertThat(eventos("TicketUpdated")).anySatisfy(e -> {
            assertThat(e.get("ticketId").asText()).isEqualTo(ticket.getId().toString());
            assertThat(e.get("cambio").asText()).isEqualTo("ASIENTO");
        });
    }

    @Test
    void cambioDeAsientoConConflictoNoCambiaNada() throws Exception {
        Ticket ticket = comprada();
        doThrow(new ApiException(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE", "El asiento 14 no está disponible"))
                .when(seatInventory).swapSeat(viajeOriginal, ticket.getBookingId(), "12", "14");

        mvc.perform(post("/tickets/{id}/seat", ticket.getId()).with(pasajero())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromSeat\":\"12\",\"toSeat\":\"14\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_UNAVAILABLE"));

        assertThat(releer(ticket.getBookingId()).getSeatNumbers()).containsExactly("12");
        assertThat(releerTiquete(ticket.getId()).getQrToken()).isEqualTo(ticket.getQrToken());
        assertThat(passengers.findByBookingId(ticket.getBookingId())).extracting(Passenger::getAsiento)
                .containsExactly("12");
    }

    @Test
    void cambioDeAsientoAMenosDeMediaHoraDeLaSalidaSeRechaza() throws Exception {
        Ticket ticket = comprada(viaje("Bogotá", "Medellín", companyId, Duration.ofMinutes(20), "95000"));

        mvc.perform(post("/tickets/{id}/seat", ticket.getId()).with(pasajero())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromSeat\":\"12\",\"toSeat\":\"14\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_CHANGE_WINDOW_CLOSED"));
        verify(seatInventory, never()).swapSeat(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ US-152

    @Test
    void laCorreccionDeUnPasajeroSoloSePermiteUnaVez() throws Exception {
        Ticket ticket = comprada();
        UUID passengerId = passengers.findByBookingId(ticket.getBookingId()).get(0).getId();
        String correccion = """
                {"nombre":"Ana María Pérez","tipoDocumento":"CC","numeroDocumento":"1020304051"}
                """;

        mvc.perform(patch("/tickets/{id}/passengers/{p}", ticket.getId(), passengerId).with(pasajero())
                        .contentType(MediaType.APPLICATION_JSON).content(correccion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pasajeros[0].passengerId").value(passengerId.toString()))
                .andExpect(jsonPath("$.pasajeros[0].nombre").value("Ana María Pérez"))
                .andExpect(jsonPath("$.pasajeros[0].numeroDocumento").value("1020304051"));
        mvc.perform(patch("/tickets/{id}/passengers/{p}", ticket.getId(), passengerId).with(pasajero())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Otra\",\"tipoDocumento\":\"CC\",\"numeroDocumento\":\"999999\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PASSENGER_ALREADY_CORRECTED"));

        Map<String, Object> cambio = jdbc.queryForMap("select * from passenger_changes where passenger_id = ?",
                passengerId);
        assertThat(cambio).containsEntry("nombre_anterior", "Ana Pérez")
                .containsEntry("numero_documento_anterior", "1020304050")
                .containsEntry("nombre_nuevo", "Ana María Pérez")
                .containsEntry("hecho_por", userId);
        // El QR no incluye los datos del pasajero: se conserva.
        assertThat(releerTiquete(ticket.getId()).getQrToken()).isEqualTo(ticket.getQrToken());
        assertThat(eventos("TicketUpdated")).anySatisfy(e -> {
            assertThat(e.get("ticketId").asText()).isEqualTo(ticket.getId().toString());
            assertThat(e.get("cambio").asText()).isEqualTo("PASAJERO");
        });
    }
}
