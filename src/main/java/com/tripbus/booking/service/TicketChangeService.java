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
import com.tripbus.booking.web.dto.TicketResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cambios del dueño sobre un tiquete EMITIDO: cambiar de asiento en el mismo viaje (US-139, hasta 30 min antes de
 * la salida) y corregir el nombre o el documento de un pasajero (US-152, una vez por pasajero y hasta 1 h antes).
 * El QR incluye los asientos pero no los datos del pasajero: solo el cambio de asiento genera un QR nuevo (y
 * reenvía el tiquete por correo). Ambos publican TicketUpdated con el campo "cambio".
 */
@Service
public class TicketChangeService {

    private static final Logger log = LoggerFactory.getLogger(TicketChangeService.class);

    static final Duration PLAZO_CAMBIO_ASIENTO = Duration.ofMinutes(30);
    static final Duration PLAZO_CORRECCION = Duration.ofHours(1);
    static final String NO_MODIFICABLE = "TICKET_NOT_MODIFIABLE";

    private static final String NO_EXISTE = "TICKET_NOT_FOUND";
    private static final String NO_ES_DE_LA_RESERVA = "SEAT_NOT_IN_BOOKING";
    private static final String NO_EXISTE_MSG = "El tiquete no existe";
    private static final String YA_CORREGIDO = "PASSENGER_ALREADY_CORRECTED";
    private static final String YA_CORREGIDO_MSG = "Los datos de este pasajero ya se corrigieron una vez";

    private final TicketRepository ticketRepository;
    private final BookingRepository bookingRepository;
    private final PassengerRepository passengerRepository;
    private final PassengerChangeRepository passengerChangeRepository;
    private final SeatInventoryClient seatInventory;
    private final CatalogClient catalog;
    private final TicketService ticketService;
    private final TicketMailService ticketMail;
    private final SagaLogger saga;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    @SuppressWarnings("java:S107") // dependencias de los cambios del tiquete, inyectadas por Spring
    public TicketChangeService(TicketRepository ticketRepository, BookingRepository bookingRepository,
                               PassengerRepository passengerRepository,
                               PassengerChangeRepository passengerChangeRepository, SeatInventoryClient seatInventory,
                               CatalogClient catalog, TicketService ticketService, TicketMailService ticketMail,
                               SagaLogger saga, EventPublisher events, TransactionTemplate tx, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.bookingRepository = bookingRepository;
        this.passengerRepository = passengerRepository;
        this.passengerChangeRepository = passengerChangeRepository;
        this.seatInventory = seatInventory;
        this.catalog = catalog;
        this.ticketService = ticketService;
        this.ticketMail = ticketMail;
        this.saga = saga;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * US-139: seat-inventory cambia el asiento (POST /internal/sales/swap) y, si responde bien, en una transacción
     * se actualizan el asiento del pasajero, bookings.seat_numbers y el QR. Si el tiquete dejó de estar EMITIDO
     * entre tanto, el cambio se deshace en seat-inventory.
     */
    public TicketResponse changeSeat(UUID ticketId, BookingViewer viewer, ChangeSeatRequest request) {
        String desde = request.fromSeat().trim();
        String hacia = request.toSeat().trim();
        Booking booking = modificable(ticketId, viewer, PLAZO_CAMBIO_ASIENTO, "SEAT_CHANGE_WINDOW_CLOSED");
        if (desde.equals(hacia)) {
            throw ApiException.badRequest("SAME_SEAT", "El asiento nuevo debe ser distinto del actual");
        }
        if (!booking.getSeatNumbers().contains(desde)) {
            throw ApiException.conflict(NO_ES_DE_LA_RESERVA, noEsDeLaReserva(desde));
        }
        if (booking.getSeatNumbers().contains(hacia)) {
            throw ApiException.conflict("SEAT_ALREADY_IN_BOOKING", "Ese asiento ya es de esta reserva: " + hacia);
        }
        try {
            seatInventory.swapSeat(booking.getItineraryId(), booking.getId(), desde, hacia);
        } catch (RemoteErrors.RemoteUnavailableException e) {
            throw ApiException.unavailable("SEAT_INVENTORY_UNAVAILABLE",
                    "No se pudo cambiar el asiento en este momento; intenta de nuevo");
        }
        try {
            tx.executeWithoutResult(s -> aplicarCambioDeAsiento(ticketId, booking.getId(), desde, hacia,
                    viewer.userId()));
        } catch (ApiException e) {
            if (NO_MODIFICABLE.equals(e.getCode())) {
                deshacerCambioDeAsiento(booking, desde, hacia);
            }
            throw e;
        }
        return ticketService.get(ticketId, viewer.userId(), true);
    }

    private void aplicarCambioDeAsiento(UUID ticketId, UUID bookingId, String desde, String hacia, UUID userId) {
        Booking booking = bookingRepository.lockById(bookingId).orElseThrow();
        List<String> asientos = booking.getSeatNumbers();
        if (asientos.contains(hacia) && !asientos.contains(desde)) {
            return; // una solicitud igual simultánea ya lo aplicó
        }
        if (!asientos.contains(desde)) {
            throw ApiException.conflict(NO_ES_DE_LA_RESERVA, noEsDeLaReserva(desde));
        }
        Instant now = clock.instant();
        booking.cambiarAsiento(desde, hacia, now);
        passengerRepository.findByBookingId(bookingId).stream()
                .filter(p -> desde.equals(p.getAsiento()))
                .forEach(p -> p.cambiarAsiento(hacia));
        // El QR lleva los asientos: uno nuevo (el viejo deja de validar) y se reenvía el tiquete por correo.
        if (!ticketService.reissueQr(ticketId, booking)) {
            throw ApiException.conflict(NO_MODIFICABLE, "El tiquete ya no se puede modificar");
        }
        Map<String, Object> payload = actualizado(booking, ticketId, "ASIENTO", userId);
        payload.put("fromSeat", desde);
        payload.put("toSeat", hacia);
        saga.log(bookingId, "ASIENTO_CAMBIADO", "OK", payload);
        events.publishAfterCommit(BookingEvents.TICKET_UPDATED, payload);
        ticketMail.sendAfterCommit(ticketId);
    }

    private void deshacerCambioDeAsiento(Booking booking, String desde, String hacia) {
        try {
            seatInventory.swapSeat(booking.getItineraryId(), booking.getId(), hacia, desde);
        } catch (RuntimeException e) {
            log.error("No se pudo deshacer en Seat Inventory el cambio {} -> {} de la reserva {}: {}", desde, hacia,
                    booking.getId(), e.getMessage());
        }
    }

    /**
     * US-152: corrige el nombre o el documento de un pasajero (una sola vez: lo exige passenger_changes). El QR
     * no incluye esos datos, así que se conserva.
     */
    public TicketResponse correctPassenger(UUID ticketId, UUID passengerId, BookingViewer viewer,
                                           PassengerRequest request) {
        UUID bookingId = modificable(ticketId, viewer, PLAZO_CORRECCION, "PASSENGER_CHANGE_WINDOW_CLOSED").getId();
        Passenger actual = passengerRepository.findById(passengerId)
                .filter(p -> p.getBookingId().equals(bookingId))
                .orElseThrow(() -> ApiException.notFound("PASSENGER_NOT_FOUND", "El pasajero no es de este tiquete"));
        if (passengerChangeRepository.existsByPassengerId(passengerId)) {
            throw ApiException.conflict(YA_CORREGIDO, YA_CORREGIDO_MSG);
        }
        String nombre = request.nombre().trim();
        String numero = request.numeroDocumento().trim();
        if (nombre.equals(actual.getNombre()) && request.tipoDocumento().equals(actual.getTipoDocumento())
                && numero.equals(actual.getNumeroDocumento())) {
            throw ApiException.badRequest("PASSENGER_UNCHANGED", "Los datos son iguales a los actuales");
        }
        try {
            tx.executeWithoutResult(s -> {
                Booking booking = bookingRepository.lockById(bookingId).orElseThrow();
                Passenger p = passengerRepository.findById(passengerId).orElseThrow();
                passengerChangeRepository.saveAndFlush(new PassengerChange(p, nombre, request.tipoDocumento(), numero,
                        viewer.userId(), clock.instant()));
                p.corregir(nombre, request.tipoDocumento(), numero);
                Map<String, Object> payload = actualizado(booking, ticketId, "PASAJERO", viewer.userId());
                payload.put("passengerId", passengerId);
                saga.log(bookingId, "PASAJERO_CORREGIDO", "OK", payload);
                events.publishAfterCommit(BookingEvents.TICKET_UPDATED, payload);
            });
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict(YA_CORREGIDO, YA_CORREGIDO_MSG);
        }
        return ticketService.get(ticketId, viewer.userId(), true);
    }

    /**
     * Tiquete del dueño, EMITIDO y con más de {@code plazo} antes de la salida. El OPERADOR (su empresa) y el
     * ADMIN pueden consultarlo pero no modificarlo (403); cualquier otro recibe 404 como si no existiera.
     */
    private Booking modificable(UUID ticketId, BookingViewer viewer, Duration plazo, String codigoPlazo) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> ApiException.notFound(NO_EXISTE, NO_EXISTE_MSG));
        Booking booking = bookingRepository.findById(ticket.getBookingId())
                .filter(viewer::canSee)
                .orElseThrow(() -> ApiException.notFound(NO_EXISTE, NO_EXISTE_MSG));
        if (!booking.getUserId().equals(viewer.userId())) {
            throw ApiException.forbidden("NOT_TICKET_OWNER", "Solo el dueño del tiquete puede modificarlo");
        }
        if (!TicketService.EMITIDO.equals(ticket.getEstado())) {
            throw new ApiException(HttpStatus.CONFLICT, NO_MODIFICABLE, "El tiquete ya no se puede modificar",
                    Map.of("estado", ticket.getEstado()));
        }
        CatalogClient.ItinerarySummary viaje = catalog.itinerary(booking.getItineraryId());
        if (viaje == null || viaje.fechaSalida() == null) {
            throw ApiException.unavailable("CATALOG_UNAVAILABLE", "No se pudo consultar el viaje; intenta de nuevo");
        }
        if (!viaje.fechaSalida().isAfter(clock.instant().plus(plazo))) {
            throw new ApiException(HttpStatus.CONFLICT, codigoPlazo,
                    "Ya no se puede hacer este cambio: faltan menos de " + plazo.toMinutes() + " min para la salida",
                    Map.of("fechaSalida", viaje.fechaSalida().toString()));
        }
        return booking;
    }

    private static String noEsDeLaReserva(String asiento) {
        return "El asiento " + asiento + " no es de esta reserva";
    }

    private static Map<String, Object> actualizado(Booking booking, UUID ticketId, String cambio, UUID userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("bookingId", booking.getId());
        payload.put("ticketId", ticketId);
        payload.put("cambio", cambio);
        return payload;
    }
}
