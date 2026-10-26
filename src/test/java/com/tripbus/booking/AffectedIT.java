package com.tripbus.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.RemoteErrors;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.common.events.ProcessedEventRepository;
import com.tripbus.booking.domain.AffectedBooking;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.repository.AffectedBookingRepository;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.service.AffectedBookingJob;
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
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 3: viajes afectados por la empresa (US-154), decisión automática al vencer el plazo (US-155) y manifiesto de
 * pasajeros (US-172), contra PostgreSQL 16 y Redis 7 reales (Testcontainers). Los eventos de catalog llegan por el
 * stream real con el consumidor encendido (idempotencia por eventId en processed_events). Seat Inventory, Catalog,
 * Flash Offers y la pasarela son mocks.
 */
@SpringBootTest(properties = {"tripbus.events.consumer-enabled=true", "tripbus.jobs.affected-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class AffectedIT {

    private static final String STREAM = "tripbus:events:test";

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
    private EventPublisher publisher;
    @Autowired
    private ProcessedEventRepository processedEvents;
    @Autowired
    private BookingRepository bookings;
    @Autowired
    private PassengerRepository passengers;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private TicketRepository tickets;
    @Autowired
    private AffectedBookingRepository affected;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private AffectedBookingJob job;
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
    private UUID viaje;
    private Instant salida;

    @BeforeEach
    void preparar() {
        reset(seatInventory, catalog, gateway);
        userId = UUID.randomUUID();
        salida = Instant.now().plus(Duration.ofDays(2));
        viaje = itinerario(companyId, salida, "PROGRAMADO");
        when(catalog.search(any(), any(), any())).thenReturn(List.of());
    }

    private UUID itinerario(UUID empresa, Instant sale, String estado) {
        UUID id = UUID.randomUUID();
        when(catalog.itinerary(id)).thenReturn(new CatalogClient.ItinerarySummary(id, "Bogotá", "Medellín", sale, null,
                "Expreso Andino", "ABC123", empresa, estado));
        when(catalog.price(id)).thenReturn(new CatalogClient.Price(id, new BigDecimal("110000"), "COP", "PROGRAMADO"));
        return id;
    }

    /** Reserva CONFIRMADA en {@code itineraryId} con su pago aprobado (95.000) y el tiquete EMITIDO. */
    private Ticket comprada(UUID itineraryId, String... asientos) {
        return tx.execute(s -> {
            Booking b = new Booking(UUID.randomUUID(), userId, itineraryId, null, List.of(asientos),
                    new BigDecimal("95000"), "COP", Instant.now(), "ana.perez@mail.com", companyId, Instant.now());
            b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, Instant.now());
            b.cambiarEstado(BookingStatus.PAGO_APROBADO, Instant.now());
            b.cambiarEstado(BookingStatus.CONFIRMADA, Instant.now());
            bookings.save(b);
            for (int i = 0; i < asientos.length; i++) {
                passengers.save(new Passenger(b.getId(), "Pasajero " + asientos[i], "CC", "10203040" + i, asientos[i]));
            }
            Payment p = new Payment(b.getId(), b.getTotal(), "k-" + UUID.randomUUID(), "4242", "VISA", Instant.now());
            p.asignarGatewayId("sbx_" + UUID.randomUUID().toString().substring(0, 8), Instant.now());
            p.aprobar(Instant.now());
            payments.save(p);
            return ticketService.issue(b);
        });
    }

    private Ticket comprada() {
        return comprada(viaje, "12");
    }

    /** Publica el evento de catalog en el stream y espera a que el consumidor lo procese. */
    private void evento(String eventId, String tipo, Map<String, Object> payload) {
        publisher.publish(eventId, tipo, payload);
        await().atMost(Duration.ofSeconds(15)).until(() -> processedEvents.existsById(eventId));
    }

    private void cancelado(UUID itineraryId) {
        evento("evt-" + UUID.randomUUID(), "ItineraryCancelled", Map.of("itineraryId", itineraryId.toString(),
                "companyId", companyId.toString(), "motivo", "Bus varado"));
    }

    private void cambiado(UUID itineraryId, String tipo, int minutos) {
        evento("evt-" + UUID.randomUUID(), "ItineraryRescheduled", Map.of("itineraryId", itineraryId.toString(),
                "companyId", companyId.toString(), "tipo", tipo, "minutosDiferencia", minutos,
                "fechaSalida", salida.plus(Duration.ofMinutes(minutos)).toString()));
    }

    private RequestPostProcessor pasajero() {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("role", "PASAJERO"))
                .authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"));
    }

    private RequestPostProcessor rol(String rol, UUID empresa) {
        return jwt().jwt(j -> {
            j.subject(UUID.randomUUID().toString()).claim("role", rol);
            if (empresa != null) {
                j.claim("companyId", empresa.toString());
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    private ResultActions decidir(UUID bookingId, String accion) throws Exception {
        return mvc.perform(post("/bookings/{id}/affected/" + accion, bookingId).with(pasajero()));
    }

    private AffectedBooking afectada(UUID bookingId) {
        return affected.findById(bookingId).orElseThrow();
    }

    private void vencerPlazo(UUID bookingId) {
        jdbc.update("update affected_bookings set plazo_decision = now() - interval '1 minute' where booking_id = ?",
                bookingId);
    }

    private List<JsonNode> eventos(String tipo) throws Exception {
        List<JsonNode> encontrados = new ArrayList<>();
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(STREAM, Range.unbounded());
        for (MapRecord<String, Object, Object> r : records == null ? List.<MapRecord<String, Object, Object>>of()
                : records) {
            if (tipo.equals(r.getValue().get("type"))) {
                encontrados.add(objectMapper.readTree(String.valueOf(r.getValue().get("payload"))));
            }
        }
        return encontrados;
    }

    private long eventosDe(String tipo, UUID bookingId) throws Exception {
        return eventos(tipo).stream().filter(e -> bookingId.toString().equals(e.path("bookingId").asText())).count();
    }

    // ------------------------------------------------------------------ eventos de catalog

    @Test
    void unEventoRepetidoSoloAfectaYAvisaUnaVez() throws Exception {
        Ticket ticket = comprada();
        Map<String, Object> payload = Map.of("itineraryId", viaje.toString(), "motivo", "Bus varado");

        evento("evt-repetido-1", "ItineraryCancelled", payload);
        publisher.publish("evt-repetido-1", "ItineraryCancelled", payload); // catalog lo reenvía
        cambiado(UUID.randomUUID(), "RETRASO", 120); // otro evento posterior: el primero ya se procesó dos veces
        AffectedBooking a = afectada(ticket.getBookingId());

        assertThat(a.getCausa()).isEqualTo(AffectedBooking.Causa.CANCELACION);
        assertThat(a.getEstado()).isEqualTo(AffectedBooking.Estado.PENDIENTE);
        assertThat(a.getPlazoDecision()).isBetween(Instant.now().plus(Duration.ofHours(23)),
                Instant.now().plus(Duration.ofHours(25)));
        assertThat(eventosDe("BookingAffected", ticket.getBookingId())).isEqualTo(1);
        JsonNode evento = eventos("BookingAffected").stream()
                .filter(e -> ticket.getBookingId().toString().equals(e.get("bookingId").asText())).findFirst()
                .orElseThrow();
        assertThat(evento.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(evento.get("ticketId").asText()).isEqualTo(ticket.getId().toString());
        assertThat(evento.get("causa").asText()).isEqualTo("CANCELACION");
        assertThat(evento.get("opciones")).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from saga_log where booking_id = ? and paso = 'RESERVA_AFECTADA'",
                Long.class, ticket.getBookingId())).isEqualTo(1);

        mvc.perform(get("/bookings/affected").with(pasajero()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookingId").value(ticket.getBookingId().toString()))
                .andExpect(jsonPath("$[0].opciones[0]").value("REPROGRAMAR"))
                .andExpect(jsonPath("$[0].opciones[1]").value("REEMBOLSO"));
    }

    @Test
    void unRetrasoMenorAlUmbralNoAfectaANadie() throws Exception {
        Ticket ticket = comprada();
        Ticket usado = comprada();
        tx.executeWithoutResult(s -> tickets.marcarUsado(usado.getId(), usado.getQrToken(), Instant.now(), UUID.randomUUID()));

        cambiado(viaje, "RETRASO", 59);
        assertThat(affected.findById(ticket.getBookingId())).isEmpty();

        cambiado(viaje, "RETRASO", 60);
        assertThat(afectada(ticket.getBookingId()).getCausa()).isEqualTo(AffectedBooking.Causa.RETRASO);
        // Un tiquete ya usado no queda afectado.
        assertThat(affected.findById(usado.getBookingId())).isEmpty();
        mvc.perform(get("/bookings/{id}/affected", ticket.getBookingId()).with(pasajero()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opciones.length()").value(3))
                .andExpect(jsonPath("$.opciones[0]").value("ACEPTAR_CAMBIO"));
        // El operador de la empresa la consulta; una reserva no afectada responde 404.
        mvc.perform(get("/bookings/{id}/affected", ticket.getBookingId()).with(rol("OPERADOR", companyId)))
                .andExpect(status().isOk());
        mvc.perform(get("/bookings/{id}/affected", usado.getBookingId()).with(pasajero()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOOKING_NOT_AFFECTED"));
    }

    // ------------------------------------------------------------------ US-154

    @Test
    void aceptarElNuevoHorarioDejaElTiqueteValidoYNoSePuedeDecidirDosVeces() throws Exception {
        Ticket ticket = comprada();
        cambiado(viaje, "REPROGRAMACION", -90);

        decidir(ticket.getBookingId(), "accept").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACEPTO_CAMBIO"))
                .andExpect(jsonPath("$.decididoPor").value("PASAJERO"));

        assertThat(tickets.findById(ticket.getId()).orElseThrow().getEstado()).isEqualTo("EMITIDO");
        decidir(ticket.getBookingId(), "refund").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_DECIDED"));
        decidir(ticket.getBookingId(), "accept").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_DECIDED"));
        verify(gateway, never()).refund(any(), any());
    }

    @Test
    void conElViajeCanceladoNoHayHorarioQueAceptar() throws Exception {
        Ticket ticket = comprada();
        cancelado(viaje);

        decidir(ticket.getBookingId(), "accept").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPTION_NOT_AVAILABLE"));
        // Y el operador consulta pero no decide.
        mvc.perform(post("/bookings/{id}/affected/refund", ticket.getBookingId()).with(rol("OPERADOR", companyId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reprogramarGratisAOtroViajeAunqueCuesteMas() throws Exception {
        Ticket ticket = comprada();
        cancelado(viaje);
        UUID otroViaje = itinerario(UUID.randomUUID(), salida.plus(Duration.ofDays(1)), "PROGRAMADO");
        UUID holdId = UUID.randomUUID();
        when(seatInventory.getHold(holdId)).thenReturn(new SeatInventoryClient.Hold(holdId, otroViaje, List.of("7"),
                userId, null, null, "ACTIVA", Instant.now().plusSeconds(600), 600));

        String body = mvc.perform(post("/bookings/{id}/affected/reschedule", ticket.getBookingId()).with(pasajero())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"holdId\":\"" + holdId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("REPROGRAMADA"))
                .andExpect(jsonPath("$.decididoPor").value("PASAJERO"))
                .andReturn().getResponse().getContentAsString();

        UUID nuevaId = UUID.fromString(objectMapper.readTree(body).get("bookingNuevoId").asText());
        Booking nueva = bookings.findById(nuevaId).orElseThrow();
        assertThat(nueva.getEstado()).isEqualTo(BookingStatus.CONFIRMADA);
        assertThat(nueva.getTotal()).isEqualByComparingTo("0"); // 110.000 > 95.000, pero sin cargo
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getEstado()).isEqualTo("REPROGRAMADO");
        verify(gateway, never()).charge(any(), any(), anyString(), anyString());
        decidir(ticket.getBookingId(), "refund").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_DECIDED"));
    }

    @Test
    void reembolsoDeUnViajeReprogramadoAnulaTiqueteYAsientos() throws Exception {
        Ticket ticket = comprada();
        cambiado(viaje, "REPROGRAMACION", 300);

        decidir(ticket.getBookingId(), "refund").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("REEMBOLSADA"));

        Booking booking = bookings.findById(ticket.getBookingId()).orElseThrow();
        assertThat(booking.getEstado()).isEqualTo(BookingStatus.REEMBOLSADA);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getEstado()).isEqualTo("ANULADO");
        verify(gateway).refund(anyString(), eq(new BigDecimal("95000.00")));
        verify(seatInventory).voidSale(viaje, booking.getId(), List.of("12"), "REEMBOLSO");
        assertThat(afectada(booking.getId()).getAnulacionResueltaEn()).isNotNull();
        assertThat(eventosDe("RefundProcessed", booking.getId())).isEqualTo(1);
        mvc.perform(post("/tickets/validate").with(rol("CONDUCTOR", companyId)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("qrCode", ticket.getQrCode()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_VOIDED"));
    }

    @Test
    void elDobleReembolsoSoloDevuelveElDineroUnaVez() throws Exception {
        Ticket ticket = comprada();
        cancelado(viaje);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch salidaHilos = new CountDownLatch(1);
        List<Future<Integer>> intentos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            intentos.add(pool.submit(() -> {
                salidaHilos.await();
                return decidir(ticket.getBookingId(), "refund").andReturn().getResponse().getStatus();
            }));
        }
        salidaHilos.countDown();
        for (Future<Integer> f : intentos) {
            assertThat(f.get()).isEqualTo(200);
        }
        pool.shutdown();
        decidir(ticket.getBookingId(), "refund").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("REEMBOLSADA"));

        verify(gateway, times(1)).refund(anyString(), any());
        assertThat(jdbc.queryForObject("select count(*) from refunds r join payments p on p.id = r.payment_id "
                + "where p.booking_id = ?", Long.class, ticket.getBookingId())).isEqualTo(1);
        // El viaje se canceló: no hay asientos que devolver a la venta.
        verify(seatInventory, never()).voidSale(any(), any(), any(), any());
    }

    @Test
    void sinCambioDeLaEmpresaNoHayReembolso() throws Exception {
        Ticket ticket = comprada();

        decidir(ticket.getBookingId(), "refund").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOOKING_NOT_AFFECTED"));
        verify(gateway, never()).refund(any(), any());
    }

    // ------------------------------------------------------------------ US-155

    @Test
    void alVencerElPlazoUnRetrasoSeDaPorAceptado() throws Exception {
        Ticket ticket = comprada();
        cambiado(viaje, "RETRASO", 120);
        vencerPlazo(ticket.getBookingId());

        decidir(ticket.getBookingId(), "refund").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DECISION_WINDOW_CLOSED"));
        job.runNow();

        AffectedBooking a = afectada(ticket.getBookingId());
        assertThat(a.getEstado()).isEqualTo(AffectedBooking.Estado.ACEPTO_CAMBIO);
        assertThat(a.getDecididoPor()).isEqualTo(AffectedBooking.DecididoPor.SISTEMA);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getEstado()).isEqualTo("EMITIDO");
    }

    @Test
    void alVencerElPlazoDeUnaCancelacionSeReubicaEnElSiguienteViajeConCupo() throws Exception {
        Ticket ticket = comprada(viaje, "12", "13");
        cancelado(viaje);
        vencerPlazo(ticket.getBookingId());
        UUID otraEmpresa = itinerario(UUID.randomUUID(), salida.plus(Duration.ofHours(2)), "PROGRAMADO");
        UUID lleno = itinerario(companyId, salida.plus(Duration.ofHours(3)), "PROGRAMADO");
        UUID siguiente = itinerario(companyId, salida.plus(Duration.ofHours(5)), "PROGRAMADO");
        UUID masTarde = itinerario(companyId, salida.plus(Duration.ofHours(8)), "PROGRAMADO");
        when(catalog.search(eq("Bogotá"), eq("Medellín"), any())).thenReturn(List.of(
                opcion(masTarde, salida.plus(Duration.ofHours(8)), 30),
                opcion(otraEmpresa, salida.plus(Duration.ofHours(2)), 30),
                opcion(lleno, salida.plus(Duration.ofHours(3)), 1),
                opcion(siguiente, salida.plus(Duration.ofHours(5)), 30)));
        when(seatInventory.availableSeats(siguiente)).thenReturn(List.of("3", "4", "9"));
        UUID holdId = UUID.randomUUID();
        SeatInventoryClient.Hold hold = new SeatInventoryClient.Hold(holdId, siguiente, List.of("3", "4"), userId, null,
                null, "ACTIVA", Instant.now().plusSeconds(600), 600);
        when(seatInventory.createHold(siguiente, List.of("3", "4"), userId)).thenReturn(hold);
        when(seatInventory.getHold(holdId)).thenReturn(hold);

        job.runNow();

        AffectedBooking a = afectada(ticket.getBookingId());
        assertThat(a.getEstado()).isEqualTo(AffectedBooking.Estado.REUBICADA);
        assertThat(a.getDecididoPor()).isEqualTo(AffectedBooking.DecididoPor.SISTEMA);
        Booking nueva = bookings.findById(a.getBookingNuevoId()).orElseThrow();
        assertThat(nueva.getItineraryId()).isEqualTo(siguiente);
        assertThat(nueva.getEstado()).isEqualTo(BookingStatus.CONFIRMADA);
        assertThat(nueva.getSeatNumbers()).containsExactly("3", "4");
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getEstado()).isEqualTo("REPROGRAMADO");
        verify(seatInventory, never()).availableSeats(otraEmpresa);
        verify(seatInventory, never()).availableSeats(lleno);
        verify(gateway, never()).refund(any(), any());
        JsonNode reubicada = eventos("BookingRelocated").stream()
                .filter(e -> ticket.getBookingId().toString().equals(e.get("bookingIdAnterior").asText()))
                .findFirst().orElseThrow();
        assertThat(reubicada.get("bookingIdNuevo").asText()).isEqualTo(nueva.getId().toString());
        assertThat(reubicada.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(reubicada.get("itineraryIdNuevo").asText()).isEqualTo(siguiente.toString());
    }

    @Test
    void alVencerElPlazoSinCupoEnTresDiasSeReembolsa() throws Exception {
        Ticket ticket = comprada();
        cancelado(viaje);
        vencerPlazo(ticket.getBookingId());
        UUID fueraDeVentana = itinerario(companyId, salida.plus(Duration.ofDays(4)), "PROGRAMADO");
        when(catalog.search(any(), any(), any())).thenReturn(List.of(opcion(fueraDeVentana,
                salida.plus(Duration.ofDays(4)), 40)));

        // Si catalog no responde no se concluye "sin cupo": sigue pendiente para la próxima pasada.
        doThrow(new RemoteErrors.RemoteUnavailableException("catalog caído", null))
                .when(catalog).search(any(), any(), any());
        job.runNow();
        assertThat(afectada(ticket.getBookingId()).getEstado()).isEqualTo(AffectedBooking.Estado.PENDIENTE);

        reset(catalog);
        when(catalog.itinerary(viaje)).thenReturn(new CatalogClient.ItinerarySummary(viaje, "Bogotá", "Medellín",
                salida, null, "Expreso Andino", "ABC123", companyId, "CANCELADO"));
        when(catalog.search(any(), any(), any())).thenReturn(List.of(opcion(fueraDeVentana,
                salida.plus(Duration.ofDays(4)), 40)));
        job.runNow();

        AffectedBooking a = afectada(ticket.getBookingId());
        assertThat(a.getEstado()).isEqualTo(AffectedBooking.Estado.REEMBOLSADA);
        assertThat(a.getDecididoPor()).isEqualTo(AffectedBooking.DecididoPor.SISTEMA);
        assertThat(bookings.findById(ticket.getBookingId()).orElseThrow().getEstado())
                .isEqualTo(BookingStatus.REEMBOLSADA);
        verify(gateway, times(1)).refund(anyString(), any());
        verify(seatInventory, never()).createHold(any(), any(), any());
        assertThat(eventosDe("RefundProcessed", ticket.getBookingId())).isEqualTo(1);
    }

    private static CatalogClient.TripOption opcion(UUID id, Instant sale, int disponibles) {
        return new CatalogClient.TripOption(id, UUID.randomUUID(), "Bogotá", "Medellín", sale, disponibles);
    }

    // ------------------------------------------------------------------ US-172

    @Test
    void elManifiestoLoVenLaEmpresaDelViajeYAdminYQuedaRegistrado() throws Exception {
        Ticket abordo = comprada(viaje, "10");
        comprada(viaje, "2", "3");
        Ticket reprogramado = comprada(viaje, "20");
        tx.executeWithoutResult(s -> tickets.marcarUsado(abordo.getId(), abordo.getQrToken(), Instant.now(), UUID.randomUUID()));
        tx.executeWithoutResult(s -> tickets.marcarReprogramado(reprogramado.getId()));

        mvc.perform(get("/itineraries/{id}/manifest", viaje).with(rol("OPERADOR", companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pasajeros.length()").value(3))
                .andExpect(jsonPath("$.pasajeros[0].asiento").value("2"))
                .andExpect(jsonPath("$.pasajeros[2].asiento").value("10"))
                .andExpect(jsonPath("$.pasajeros[2].estadoTiquete").value("USADO"))
                .andExpect(jsonPath("$.pasajeros[2].abordadoEn").isNotEmpty())
                .andExpect(jsonPath("$.pasajeros[0].numeroDocumento").isNotEmpty())
                .andExpect(jsonPath("$.totales.vendidos").value(3))
                .andExpect(jsonPath("$.totales.abordados").value(1))
                .andExpect(jsonPath("$.totales.pendientes").value(2));
        mvc.perform(get("/itineraries/{id}/manifest", viaje).with(rol("CONDUCTOR", companyId)))
                .andExpect(status().isOk());
        mvc.perform(get("/itineraries/{id}/manifest", viaje).with(rol("ADMIN", null)))
                .andExpect(status().isOk());
        mvc.perform(get("/itineraries/{id}/manifest", viaje).param("format", "csv").with(rol("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "nombre,tipoDocumento,numeroDocumento,asiento,bookingId,estadoTiquete,abordadoEn\n")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("vendidos,3")));

        mvc.perform(get("/itineraries/{id}/manifest", viaje).with(rol("OPERADOR", UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_NOT_FOUND"));
        mvc.perform(get("/itineraries/{id}/manifest", viaje).with(rol("CONDUCTOR", UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/itineraries/{id}/manifest", viaje).with(pasajero()))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select count(*) from manifest_access_log where itinerary_id = ? and permitido",
                Long.class, viaje)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from manifest_access_log where itinerary_id = ? and not permitido",
                Long.class, viaje)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from manifest_access_log where itinerary_id = ? and formato = 'csv'",
                Long.class, viaje)).isEqualTo(1);
    }
}
