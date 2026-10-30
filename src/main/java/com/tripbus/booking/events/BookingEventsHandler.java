package com.tripbus.booking.events;

import com.tripbus.booking.common.events.DomainEventHandler;
import com.tripbus.booking.common.events.EventEnvelope;
import com.tripbus.booking.service.AffectedBookingService;
import com.tripbus.booking.service.BookingService;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
                    UUID itineraryId = UUID.fromString(payload.get(ITINERARY_ID).asText());
                    bookingService.onItineraryRescheduled(itineraryId, instante(payload.path("fechaSalida")));
                    affectedService.onItineraryRescheduled(itineraryId, payload.path("tipo").asText(""),
                            payload.path("minutosDiferencia").asLong(0));
                }
            }
            default -> {
                // tipo no registrado en eventTypes(): no llega aquí
            }
        }
    }

    /** Fecha del evento: texto ISO-8601 o segundos desde epoch (según cómo la serialice catalog); null si no viene. */
    static Instant instante(JsonNode value) {
        if (value.isNumber()) {
            BigDecimal segundos = value.decimalValue();
            return Instant.ofEpochSecond(segundos.longValue(),
                    segundos.remainder(BigDecimal.ONE).movePointRight(9).longValue());
        }
        if (value.isTextual()) {
            try {
                return Instant.parse(value.asText());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }
}
