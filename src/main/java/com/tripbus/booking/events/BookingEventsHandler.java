package com.tripbus.booking.events;

import com.tripbus.booking.common.events.DomainEventHandler;
import com.tripbus.booking.common.events.EventEnvelope;
import com.tripbus.booking.service.AffectedBookingService;
import com.tripbus.booking.service.BookingService;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * HoldExpired (Seat Inventory): la reserva sin pagar pasa a EXPIRADA. ItineraryCancelled e ItineraryRescheduled
 * (Catalog, US-154): las reservas confirmadas del viaje quedan afectadas. La idempotencia por eventId la da
 * EventConsumer (processed_events, en la misma transacción que el manejador).
 */
@Component
public class BookingEventsHandler implements DomainEventHandler {

    private static final String ITINERARY_ID = "itineraryId";

    private final BookingService bookingService;
    private final AffectedBookingService affectedService;

    public BookingEventsHandler(BookingService bookingService, AffectedBookingService affectedService) {
        this.bookingService = bookingService;
        this.affectedService = affectedService;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(BookingEvents.HOLD_EXPIRED, BookingEvents.ITINERARY_CANCELLED,
                BookingEvents.ITINERARY_RESCHEDULED);
    }

    @Override
    public void handle(EventEnvelope event) {
        var payload = event.payload();
        switch (event.type()) {
            case BookingEvents.HOLD_EXPIRED -> {
                if (payload.hasNonNull("holdId")) {
                    bookingService.onHoldExpired(UUID.fromString(payload.get("holdId").asText()));
                }
            }
            case BookingEvents.ITINERARY_CANCELLED -> {
                if (payload.hasNonNull(ITINERARY_ID)) {
                    affectedService.onItineraryCancelled(UUID.fromString(payload.get(ITINERARY_ID).asText()));
                }
            }
            case BookingEvents.ITINERARY_RESCHEDULED -> {
                if (payload.hasNonNull(ITINERARY_ID)) {
                    affectedService.onItineraryRescheduled(UUID.fromString(payload.get(ITINERARY_ID).asText()),
                            payload.path("tipo").asText(""), payload.path("minutosDiferencia").asLong(0));
                }
            }
            default -> {
                // tipo no registrado en eventTypes(): no llega aquí
            }
        }
    }
}
