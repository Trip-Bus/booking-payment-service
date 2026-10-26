package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.BookingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-150: validaciones y cálculo de la diferencia al reprogramar, con los servicios simulados. */
class RescheduleServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID EMPRESA = UUID.randomUUID();

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PassengerRepository passengers = mock(PassengerRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final BookingService bookingService = mock(BookingService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID viajeNuevo = UUID.randomUUID();
    private RescheduleService service;
    private Booking origen;
    private BookingViewer dueno;

    @BeforeEach
    void preparar() {
        service = servicio(BigDecimal.ZERO);
        origen = confirmada(null);
        dueno = new BookingViewer(userId, false, false, null);
        when(bookings.findById(origen.getId())).thenReturn(Optional.of(origen));
        when(bookings.lockById(origen.getId())).thenReturn(Optional.of(origen));
        when(bookings.findByHoldId(any())).thenReturn(Optional.empty());
        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        tiquete(origen, "EMITIDO");
        when(passengers.findByBookingId(origen.getId())).thenReturn(List.of(
                new Passenger(origen.getId(), "Ana", "CC", "1020", "12")));
        viaje(origen.getItineraryId(), "Bogotá", "Medellín", EMPRESA, AHORA.plus(Duration.ofDays(2)), "PROGRAMADO");
        viaje(viajeNuevo, " bogotá ", "MEDELLÍN", EMPRESA, AHORA.plus(Duration.ofDays(4)), "PROGRAMADO");
        precio("120000", "COP");
        when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(bookingService.get(any(), any())).thenAnswer(inv -> respuesta(inv.getArgument(0)));
    }

    private RescheduleService servicio(BigDecimal cargo) {
        return new RescheduleService(bookings, passengers, tickets, seatInventory, catalog, bookingService,
                paymentService, mock(SagaLogger.class), mock(EventPublisher.class), tx,
                new BookingProperties(null, null, null, null, "s", null, null, cargo), Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private Booking confirmada(UUID origenId) {
        Booking b = new Booking(UUID.randomUUID(), userId, UUID.randomUUID(), null, List.of("12"),
                new BigDecimal("95000"), "COP", AHORA, "ana@mail.co", EMPRESA, AHORA);
        if (origenId != null) {
            Booking anterior = mock(Booking.class);
            when(anterior.getId()).thenReturn(origenId);
            when(anterior.getUserId()).thenReturn(userId);
            when(anterior.getMoneda()).thenReturn("COP");
            b = Booking.reprogramacion(anterior, new Booking.SeatSelection(UUID.randomUUID(), UUID.randomUUID(), null,
                    List.of("12"), AHORA), new BigDecimal("95000"), BigDecimal.ZERO, new BigDecimal("95000"), null, AHORA);
            b.cambiarEstado(BookingStatus.CONFIRMADA, AHORA);
            return b;
        }
        b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
        b.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
        b.cambiarEstado(BookingStatus.CONFIRMADA, AHORA);
        return b;
    }

    private void tiquete(Booking b, String estado) {
        Ticket t = mock(Ticket.class);
        when(t.getEstado()).thenReturn(estado);
        when(tickets.findByBookingId(b.getId())).thenReturn(Optional.of(t));
    }

    private void viaje(UUID id, String de, String a, UUID empresa, Instant salida, String estado) {
        when(catalog.itinerary(id)).thenReturn(new CatalogClient.ItinerarySummary(id, de, a, salida, null, "Expreso",
                "ABC", empresa, estado));
    }

    private void precio(String valor, String moneda) {
        when(bookingService.precio(any(), any())).thenReturn(new BookingService.Precio(new BigDecimal(valor), moneda));
    }

    private UUID retencion(UUID dueno, UUID itinerario, String estado, long segundos, String... asientos) {
        UUID holdId = UUID.randomUUID();
        when(seatInventory.getHold(holdId)).thenReturn(new SeatInventoryClient.Hold(holdId, itinerario,
                List.of(asientos), dueno, null, null, estado, AHORA.plusSeconds(600), segundos));
        return holdId;
    }

    private UUID retencionValida() {
        return retencion(userId, viajeNuevo, "ACTIVA", 600, "20");
    }

    private static BookingResponse respuesta(UUID id) {
        return new BookingResponse(id, null, null, null, null, List.of(), List.of(), null, null, null, null, null,
                null, null, null, null);
    }

    private void esperarError(UUID holdId, HttpStatus status, String codigo) {
        assertThatThrownBy(() -> service.reschedule(origen.getId(), holdId, dueno, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(codigo);
                });
    }

    private Booking creada() {
        ArgumentCaptor<Booking> nueva = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).save(nueva.capture());
        return nueva.getValue();
    }

    @Test
    void conDiferenciaQuedaPendienteDePagoConLosMismosPasajeros() {
        var result = service.reschedule(origen.getId(), retencionValida(), dueno, "nuevo@mail.co");

        Booking nueva = creada();
        assertThat(result.created()).isTrue();
        assertThat(nueva.getEstado()).isEqualTo(BookingStatus.PENDIENTE_PAGO);
        assertThat(nueva.getTotal()).isEqualByComparingTo("25000");
        assertThat(nueva.getValorPagado()).isEqualByComparingTo("120000");
        assertThat(nueva.getEmailContacto()).isEqualTo("nuevo@mail.co");
        ArgumentCaptor<Passenger> pasajero = ArgumentCaptor.forClass(Passenger.class);
        verify(passengers).save(pasajero.capture());
        assertThat(pasajero.getValue().getNombre()).isEqualTo("Ana");
        assertThat(pasajero.getValue().getAsiento()).isEqualTo("20");
        verify(paymentService, never()).confirmAndIssue(any());
    }

    @Test
    void sinDiferenciaSeConfirmaDeInmediatoYConservaElPasajePagado() {
        precio("80000", "COP");

        service.reschedule(origen.getId(), retencionValida(), dueno, null);

        Booking nueva = creada();
        assertThat(nueva.getEstado()).isEqualTo(BookingStatus.PAGO_APROBADO);
        assertThat(nueva.getTotal()).isEqualByComparingTo("0");
        assertThat(nueva.getValorPagado()).isEqualByComparingTo("95000");
        assertThat(nueva.getEmailContacto()).isEqualTo("ana@mail.co");
        verify(paymentService).confirmAndIssue(nueva.getId());
    }

    @Test
    void elCargoSeSumaAunqueElViajeNuevoSeaMasBarato() {
        service = servicio(new BigDecimal("5000"));
        precio("80000", "COP");

        service.reschedule(origen.getId(), retencionValida(), dueno, null);

        assertThat(creada().getTotal()).isEqualByComparingTo("5000");
        assertThat(RescheduleService.diferencia(new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("7")))
                .isEqualByComparingTo("47");
    }

    @Test
    void reservaAjenaOInexistente() {
        BookingViewer otro = new BookingViewer(UUID.randomUUID(), false, false, null);
        BookingViewer admin = new BookingViewer(UUID.randomUUID(), true, false, null);
        UUID holdId = retencionValida();

        assertThatThrownBy(() -> service.reschedule(origen.getId(), holdId, otro, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("BOOKING_NOT_FOUND"));
        assertThatThrownBy(() -> service.reschedule(origen.getId(), holdId, admin, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_BOOKING_OWNER"));
    }

    @Test
    void laMismaRetencionDevuelveLaReprogramacionYaCreadaYOtraUsadaEs409() {
        Booking yaCreada = mock(Booking.class);
        when(yaCreada.getId()).thenReturn(UUID.randomUUID());
        when(yaCreada.getOrigenBookingId()).thenReturn(origen.getId());
        UUID holdId = UUID.randomUUID();
        when(bookings.findByHoldId(holdId)).thenReturn(Optional.of(yaCreada));

        var result = service.reschedule(origen.getId(), holdId, dueno, null);
        assertThat(result.created()).isFalse();
        assertThat(result.booking().bookingId()).isEqualTo(yaCreada.getId());

        when(yaCreada.getOrigenBookingId()).thenReturn(null);
        esperarError(holdId, HttpStatus.CONFLICT, "HOLD_ALREADY_USED");
    }

    @Test
    void laReservaDebeEstarConfirmadaConElTiqueteEmitido() {
        UUID holdId = retencionValida();
        tiquete(origen, "USADO");
        esperarError(holdId, HttpStatus.CONFLICT, "TICKET_NOT_RESCHEDULABLE");

        when(tickets.findByBookingId(origen.getId())).thenReturn(Optional.empty());
        esperarError(holdId, HttpStatus.CONFLICT, "TICKET_NOT_RESCHEDULABLE");

        origen.cambiarEstado(BookingStatus.REPROGRAMADA, AHORA);
        esperarError(holdId, HttpStatus.CONFLICT, "BOOKING_NOT_RESCHEDULABLE");
    }

    @Test
    void elLimiteCuentaTodaLaCadena() {
        Booking primera = confirmada(UUID.randomUUID());
        Booking segunda = confirmada(primera.getId());
        when(bookings.findById(primera.getId())).thenReturn(Optional.of(primera));
        when(bookings.findById(segunda.getId())).thenReturn(Optional.of(segunda));

        assertThat(service.reprogramaciones(origen)).isZero();
        assertThat(service.reprogramaciones(primera)).isEqualTo(1);
        assertThat(service.reprogramaciones(segunda)).isEqualTo(2);

        origen = segunda;
        tiquete(segunda, "EMITIDO");
        esperarError(retencionValida(), HttpStatus.CONFLICT, "RESCHEDULE_LIMIT_REACHED");
    }

    @Test
    void unaReprogramacionVivaImpideOtra() {
        when(bookings.existsByOrigenBookingIdAndEstadoIn(origen.getId(), RescheduleService.VIVAS)).thenReturn(true);

        esperarError(retencionValida(), HttpStatus.CONFLICT, "RESCHEDULE_IN_PROGRESS");
    }

    @Test
    void fueraDePlazoOSinCatalog() {
        UUID holdId = retencionValida();
        viaje(origen.getItineraryId(), "Bogotá", "Medellín", EMPRESA, AHORA.plus(Duration.ofHours(3)), "PROGRAMADO");
        esperarError(holdId, HttpStatus.CONFLICT, "RESCHEDULE_WINDOW_CLOSED");

        when(catalog.itinerary(origen.getItineraryId())).thenReturn(null);
        esperarError(holdId, HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE");

        viaje(origen.getItineraryId(), "Bogotá", "Medellín", EMPRESA, null, "PROGRAMADO");
        esperarError(holdId, HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE");
    }

    @Test
    void retencionEquivocada() {
        esperarError(retencion(UUID.randomUUID(), viajeNuevo, "ACTIVA", 600, "20"), HttpStatus.CONFLICT,
                "HOLD_NOT_OWNED");
        esperarError(retencion(userId, viajeNuevo, "LIBERADA", 600, "20"), HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE");
        esperarError(retencion(userId, viajeNuevo, "ACTIVA", 0, "20"), HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE");
        esperarError(retencion(userId, viajeNuevo, "ACTIVA", 600, "20", "21"), HttpStatus.CONFLICT,
                "SEATS_PASSENGERS_MISMATCH");
        esperarError(retencion(userId, origen.getItineraryId(), "ACTIVA", 600, "20"), HttpStatus.CONFLICT,
                "SAME_ITINERARY");
    }

    @Test
    void elViajeNuevoDebeEstarProgramadoEnElFuturoYSerDeLaMismaRutaYEmpresa() {
        UUID holdId = retencionValida();
        when(catalog.itinerary(viajeNuevo)).thenReturn(null);
        esperarError(holdId, HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE");

        viaje(viajeNuevo, "Bogotá", "Medellín", EMPRESA, AHORA.plusSeconds(3600), "CANCELADO");
        esperarError(holdId, HttpStatus.CONFLICT, "ITINERARY_NOT_AVAILABLE");

        viaje(viajeNuevo, "Bogotá", "Medellín", EMPRESA, AHORA.minusSeconds(60), "PROGRAMADO");
        esperarError(holdId, HttpStatus.CONFLICT, "ITINERARY_DEPARTED");

        viaje(viajeNuevo, "Bogotá", "Medellín", EMPRESA, null, "PROGRAMADO");
        esperarError(holdId, HttpStatus.CONFLICT, "ITINERARY_DEPARTED");

        viaje(viajeNuevo, "Bogotá", "Cali", EMPRESA, AHORA.plusSeconds(86400), "PROGRAMADO");
        esperarError(holdId, HttpStatus.CONFLICT, "ROUTE_MISMATCH");

        viaje(viajeNuevo, "Tunja", "Medellín", EMPRESA, AHORA.plusSeconds(86400), "PROGRAMADO");
        esperarError(holdId, HttpStatus.CONFLICT, "ROUTE_MISMATCH");

        viaje(viajeNuevo, null, "Medellín", EMPRESA, AHORA.plusSeconds(86400), "PROGRAMADO");
        esperarError(holdId, HttpStatus.CONFLICT, "ROUTE_MISMATCH");

        viaje(viajeNuevo, "Bogotá", "Medellín", UUID.randomUUID(), AHORA.plusSeconds(86400), "PROGRAMADO");
        esperarError(holdId, HttpStatus.CONFLICT, "COMPANY_MISMATCH");
    }

    @Test
    void sinEmpresaEnCatalogSeUsaLaDeLaReservaYSinNingunaNoSePuede() {
        UUID holdId = retencionValida();
        viaje(origen.getItineraryId(), "Bogotá", "Medellín", null, AHORA.plus(Duration.ofDays(2)), "PROGRAMADO");
        service.reschedule(origen.getId(), holdId, dueno, null);
        assertThat(creada().getCompanyId()).isEqualTo(EMPRESA);

        Booking sinEmpresa = new Booking(UUID.randomUUID(), userId, origen.getItineraryId(), null, List.of("12"),
                new BigDecimal("95000"), "COP", AHORA, AHORA);
        sinEmpresa.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
        sinEmpresa.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
        sinEmpresa.cambiarEstado(BookingStatus.CONFIRMADA, AHORA);
        when(bookings.findById(sinEmpresa.getId())).thenReturn(Optional.of(sinEmpresa));
        tiquete(sinEmpresa, "EMITIDO");
        when(passengers.findByBookingId(sinEmpresa.getId())).thenReturn(List.of(
                new Passenger(sinEmpresa.getId(), "Ana", "CC", "1020", "12")));
        origen = sinEmpresa;
        esperarError(holdId, HttpStatus.CONFLICT, "COMPANY_MISMATCH");
    }

    @Test
    void elViajeNuevoDebeVenderseEnLaMismaMoneda() {
        precio("120", "USD");

        esperarError(retencionValida(), HttpStatus.CONFLICT, "CURRENCY_MISMATCH");
    }

    @Test
    void siOtraSolicitudGanaMientrasTantoResponde409() {
        UUID holdId = retencionValida();
        // Al validar no había ninguna; al bloquear la original ya existe la de la otra solicitud.
        when(bookings.existsByOrigenBookingIdAndEstadoIn(origen.getId(), RescheduleService.VIVAS))
                .thenReturn(false, true);

        esperarError(holdId, HttpStatus.CONFLICT, "RESCHEDULE_IN_PROGRESS");
        verify(bookings, never()).save(any());
    }

    @Test
    void choqueEnLaBdConLaMismaRetencionDevuelveLaGanadoraYSinElla409() {
        UUID holdId = retencionValida();
        doThrow(new DataIntegrityViolationException("uq_bookings_reprogramacion_viva")).when(tx).execute(any());
        esperarError(holdId, HttpStatus.CONFLICT, "RESCHEDULE_IN_PROGRESS");

        Booking ganadora = mock(Booking.class);
        when(ganadora.getId()).thenReturn(UUID.randomUUID());
        when(ganadora.getOrigenBookingId()).thenReturn(origen.getId());
        when(bookings.findByHoldId(holdId)).thenReturn(Optional.empty()).thenReturn(Optional.of(ganadora));

        var result = service.reschedule(origen.getId(), holdId, dueno, null);
        assertThat(result.created()).isFalse();
        assertThat(result.booking().bookingId()).isEqualTo(ganadora.getId());
        verify(paymentService, never()).confirmAndIssue(eq(ganadora.getId()));
    }
}
