package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.RemoteErrors;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.PassengerChange;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.events.BookingEvents;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerChangeRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.ChangeSeatRequest;
import com.tripbus.booking.web.dto.PassengerRequest;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-139 (cambio de asiento) y US-152 (corrección del pasajero), con los servicios simulados. */
class TicketChangeServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-20T12:00:00Z");

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PassengerRepository passengers = mock(PassengerRepository.class);
    private final PassengerChangeRepository changes = mock(PassengerChangeRepository.class);
    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final TicketMailService ticketMail = mock(TicketMailService.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private final UUID userId = UUID.randomUUID();
    private TicketChangeService service;
    private Booking booking;
    private Ticket ticket;
    private Passenger pasajero;
    private BookingViewer dueno;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        service = new TicketChangeService(tickets, bookings, passengers, changes, seatInventory, catalog,
                ticketService, ticketMail, mock(SagaLogger.class), events, tx, Clock.fixed(AHORA, ZoneOffset.UTC));
        doAnswer(inv -> {
            inv.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any(Consumer.class));
        booking = new Booking(UUID.randomUUID(), userId, UUID.randomUUID(), null, List.of("12"),
                new BigDecimal("95000"), "COP", AHORA, null, UUID.randomUUID(), AHORA);
        ticket = mock(Ticket.class);
        when(ticket.getId()).thenReturn(UUID.randomUUID());
        when(ticket.getBookingId()).thenReturn(booking.getId());
        when(ticket.getEstado()).thenReturn("EMITIDO");
        pasajero = new Passenger(booking.getId(), "Ana Pérez", "CC", "1020304050", "12");
        dueno = new BookingViewer(userId, false, false, null);
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookings.lockById(booking.getId())).thenReturn(Optional.of(booking));
        when(passengers.findByBookingId(booking.getId())).thenReturn(List.of(pasajero));
        when(passengers.findById(pasajero.getId())).thenReturn(Optional.of(pasajero));
        when(ticketService.reissueQr(eq(ticket.getId()), any())).thenReturn(true);
        sale(AHORA.plus(Duration.ofHours(2)));
    }

    private void sale(Instant salida) {
        when(catalog.itinerary(booking.getItineraryId())).thenReturn(new CatalogClient.ItinerarySummary(
                booking.getItineraryId(), "Bogotá", "Medellín", salida, null, "Expreso", "ABC"));
    }

    private void cambiar(String desde, String hacia) {
        service.changeSeat(ticket.getId(), dueno, new ChangeSeatRequest(desde, hacia));
    }

    private static void esperar(ThrowableAssert.ThrowingCallable call, HttpStatus status, String codigo) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(codigo);
        });
    }

    // ------------------------------------------------------------------ US-139

    @Test
    @SuppressWarnings("unchecked")
    void cambioDeAsientoActualizaReservaPasajeroYQrYReenviaElCorreo() {
        cambiar(" 12 ", "14");

        verify(seatInventory).swapSeat(booking.getItineraryId(), booking.getId(), "12", "14");
        assertThat(booking.getSeatNumbers()).containsExactly("14");
        assertThat(pasajero.getAsiento()).isEqualTo("14");
        verify(ticketService).reissueQr(ticket.getId(), booking);
        verify(ticketMail).sendAfterCommit(ticket.getId());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publishAfterCommit(eq(BookingEvents.TICKET_UPDATED), payload.capture());
        assertThat(payload.getValue()).containsEntry("cambio", "ASIENTO").containsEntry("userId", userId)
                .containsEntry("bookingId", booking.getId()).containsEntry("ticketId", ticket.getId());
        verify(ticketService).get(ticket.getId(), userId, true);
    }

    @Test
    void validacionesDelCambioDeAsiento() {
        esperar(() -> cambiar("12", "12"), HttpStatus.BAD_REQUEST, "SAME_SEAT");
        esperar(() -> cambiar("30", "14"), HttpStatus.CONFLICT, "SEAT_NOT_IN_BOOKING");
        Booking dos = new Booking(UUID.randomUUID(), userId, booking.getItineraryId(), null, List.of("12", "13"),
                BigDecimal.TEN, "COP", AHORA, AHORA);
        when(ticket.getBookingId()).thenReturn(dos.getId());
        when(bookings.findById(dos.getId())).thenReturn(Optional.of(dos));
        esperar(() -> cambiar("12", "13"), HttpStatus.CONFLICT, "SEAT_ALREADY_IN_BOOKING");
        verify(seatInventory, never()).swapSeat(any(), any(), any(), any());
    }

    @Test
    void conflictoEnSeatInventoryNoCambiaNada() {
        doThrow(new ApiException(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE", "ocupado"))
                .when(seatInventory).swapSeat(any(), any(), eq("12"), eq("14"));

        esperar(() -> cambiar("12", "14"), HttpStatus.CONFLICT, "SEAT_UNAVAILABLE");

        assertThat(booking.getSeatNumbers()).containsExactly("12");
        verify(ticketService, never()).reissueQr(any(), any());
    }

    @Test
    void seatInventoryCaidoResponde503() {
        doThrow(new RemoteErrors.RemoteUnavailableException("caído", null))
                .when(seatInventory).swapSeat(any(), any(), any(), any());

        esperar(() -> cambiar("12", "14"), HttpStatus.SERVICE_UNAVAILABLE, "SEAT_INVENTORY_UNAVAILABLE");
    }

    @Test
    void siElTiqueteSeUsoEntreTantoSeDeshaceElCambioEnSeatInventory() {
        when(ticketService.reissueQr(eq(ticket.getId()), any())).thenReturn(false);

        esperar(() -> cambiar("12", "14"), HttpStatus.CONFLICT, TicketChangeService.NO_MODIFICABLE);
        verify(seatInventory).swapSeat(booking.getItineraryId(), booking.getId(), "14", "12");

        // Si deshacerlo también falla, se registra y se responde el mismo 409.
        doThrow(new RemoteErrors.RemoteUnavailableException("caído", null))
                .when(seatInventory).swapSeat(any(), any(), eq("14"), eq("12"));
        booking.cambiarAsiento("14", "12", AHORA);
        esperar(() -> cambiar("12", "14"), HttpStatus.CONFLICT, TicketChangeService.NO_MODIFICABLE);
    }

    @Test
    void unaSolicitudIgualSimultaneaYaAplicadaNoHaceNadaMas() {
        Booking yaCambiada = new Booking(booking.getHoldId(), userId, booking.getItineraryId(), null, List.of("14"),
                BigDecimal.TEN, "COP", AHORA, AHORA);
        when(bookings.lockById(booking.getId())).thenReturn(Optional.of(yaCambiada));

        cambiar("12", "14");

        verify(ticketService, never()).reissueQr(any(), any());
        verify(events, never()).publishAfterCommit(any(), any());
    }

    @Test
    void siOtroCambioSeLlevoElAsientoEntreTantoResponde409SinDeshacer() {
        Booking otra = new Booking(booking.getHoldId(), userId, booking.getItineraryId(), null, List.of("15"),
                BigDecimal.TEN, "COP", AHORA, AHORA);
        when(bookings.lockById(booking.getId())).thenReturn(Optional.of(otra));

        esperar(() -> cambiar("12", "14"), HttpStatus.CONFLICT, "SEAT_NOT_IN_BOOKING");
        verify(seatInventory, never()).swapSeat(any(), any(), eq("14"), eq("12"));
    }

    @Test
    void soloElDuenoYConElTiqueteEmitidoYDentroDelPlazo() {
        BookingViewer admin = new BookingViewer(UUID.randomUUID(), true, false, null);
        BookingViewer otro = new BookingViewer(UUID.randomUUID(), false, false, null);
        esperar(() -> service.changeSeat(ticket.getId(), admin, new ChangeSeatRequest("12", "14")),
                HttpStatus.FORBIDDEN, "NOT_TICKET_OWNER");
        esperar(() -> service.changeSeat(ticket.getId(), otro, new ChangeSeatRequest("12", "14")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        esperar(() -> service.changeSeat(UUID.randomUUID(), dueno, new ChangeSeatRequest("12", "14")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        sale(AHORA.plus(Duration.ofMinutes(30)));
        esperar(() -> cambiar("12", "14"), HttpStatus.CONFLICT, "SEAT_CHANGE_WINDOW_CLOSED");

        when(catalog.itinerary(booking.getItineraryId())).thenReturn(null);
        esperar(() -> cambiar("12", "14"), HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE");
        sale(null);
        esperar(() -> cambiar("12", "14"), HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE");

        when(ticket.getEstado()).thenReturn("USADO");
        esperar(() -> cambiar("12", "14"), HttpStatus.CONFLICT, TicketChangeService.NO_MODIFICABLE);
        verify(seatInventory, never()).swapSeat(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ US-152

    private void corregir(UUID passengerId, String nombre, String tipo, String numero) {
        service.correctPassenger(ticket.getId(), passengerId, dueno, new PassengerRequest(nombre, tipo, numero));
    }

    @Test
    void laCorreccionGuardaLosValoresAnterioresYConservaElQr() {
        corregir(pasajero.getId(), " Ana María Pérez ", "CC", "1020304051");

        ArgumentCaptor<PassengerChange> cambio = ArgumentCaptor.forClass(PassengerChange.class);
        verify(changes).saveAndFlush(cambio.capture());
        assertThat(cambio.getValue().getPassengerId()).isEqualTo(pasajero.getId());
        assertThat(cambio.getValue().getNombreAnterior()).isEqualTo("Ana Pérez");
        assertThat(cambio.getValue().getNumeroDocumentoAnterior()).isEqualTo("1020304050");
        assertThat(cambio.getValue().getNombreNuevo()).isEqualTo("Ana María Pérez");
        assertThat(cambio.getValue().getHechoPor()).isEqualTo(userId);
        assertThat(cambio.getValue().getId()).isNotNull();
        assertThat(pasajero.getNombre()).isEqualTo("Ana María Pérez");
        assertThat(pasajero.getNumeroDocumento()).isEqualTo("1020304051");
        verify(ticketService, never()).reissueQr(any(), any());
        verify(events).publishAfterCommit(eq(BookingEvents.TICKET_UPDATED), anyMap());
    }

    @Test
    void laCorreccionRepetidaResponde409() {
        when(changes.existsByPassengerId(pasajero.getId())).thenReturn(true);
        esperar(() -> corregir(pasajero.getId(), "Otra", "CC", "999999"), HttpStatus.CONFLICT,
                "PASSENGER_ALREADY_CORRECTED");

        // Dos correcciones simultáneas: la BD deja pasar solo una.
        when(changes.existsByPassengerId(pasajero.getId())).thenReturn(false);
        when(changes.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_passenger_changes"));
        esperar(() -> corregir(pasajero.getId(), "Otra", "CC", "999999"), HttpStatus.CONFLICT,
                "PASSENGER_ALREADY_CORRECTED");
    }

    @Test
    void validacionesDeLaCorreccion() {
        esperar(() -> corregir(pasajero.getId(), "Ana Pérez", "CC", "1020304050"), HttpStatus.BAD_REQUEST,
                "PASSENGER_UNCHANGED");
        esperar(() -> corregir(UUID.randomUUID(), "Otra", "CC", "999999"), HttpStatus.NOT_FOUND,
                "PASSENGER_NOT_FOUND");
        Passenger deOtraReserva = new Passenger(UUID.randomUUID(), "X", "CC", "1111", "1");
        when(passengers.findById(deOtraReserva.getId())).thenReturn(Optional.of(deOtraReserva));
        esperar(() -> corregir(deOtraReserva.getId(), "Otra", "CC", "999999"), HttpStatus.NOT_FOUND,
                "PASSENGER_NOT_FOUND");
        // Solo cambia el tipo o el número: sí es una corrección.
        corregir(pasajero.getId(), "Ana Pérez", "CE", "1020304050");
        assertThat(pasajero.getTipoDocumento()).isEqualTo("CE");

        sale(AHORA.plus(Duration.ofMinutes(59)));
        esperar(() -> corregir(pasajero.getId(), "Otra", "CC", "999999"), HttpStatus.CONFLICT,
                "PASSENGER_CHANGE_WINDOW_CLOSED");
    }

    @Test
    void cambiarSoloElNumeroTambienEsUnaCorreccion() {
        corregir(pasajero.getId(), "Ana Pérez", "CC", "1020304059");

        assertThat(pasajero.getNumeroDocumento()).isEqualTo("1020304059");
    }
}
