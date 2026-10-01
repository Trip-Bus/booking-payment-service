package com.tripbus.booking.events;

import com.tripbus.booking.common.events.DomainEventHandler;
import com.tripbus.booking.common.events.EventEnvelope;
import com.tripbus.booking.service.BookingService;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** HoldExpired (Seat Inventory): la reserva sin pagar pasa a EXPIRADA. */
@Component
public class BookingEventsHandler implements DomainEventHandler {

    private final BookingService bookingService;

    public BookingEventsHandler(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(BookingEvents.HOLD_EXPIRED);
    }

    @Override
    public void handle(EventEnvelope event) {
        if (event.payload().hasNonNull("holdId")) {
            bookingService.onHoldExpired(UUID.fromString(event.payload().get("holdId").asText()));
        }
    }
}
