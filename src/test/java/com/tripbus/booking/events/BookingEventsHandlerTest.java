package com.tripbus.booking.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.common.events.EventEnvelope;
import com.tripbus.booking.service.AffectedBookingService;
import com.tripbus.booking.service.BookingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Despacho de los eventos que consume booking (HoldExpired e ItineraryCancelled/Rescheduled de catalog). */
class BookingEventsHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BookingService bookingService = mock(BookingService.class);
    private final AffectedBookingService affectedService = mock(AffectedBookingService.class);
    private final BookingEventsHandler handler = new BookingEventsHandler(bookingService, affectedService);

    private static EventEnvelope evento(String tipo, String json) throws Exception {
        return new EventEnvelope("e1", tipo, "catalog-service", Instant.now(), MAPPER.readTree(json));
    }

    @Test
    void despachaCadaTipoAlServicioQueLoAtiende() throws Exception {
        UUID id = UUID.randomUUID();
        handler.handle(evento("HoldExpired", "{\"holdId\":\"" + id + "\"}"));
        handler.handle(evento("ItineraryCancelled", "{\"itineraryId\":\"" + id + "\"}"));
        handler.handle(evento("ItineraryRescheduled",
                "{\"itineraryId\":\"" + id + "\",\"tipo\":\"RETRASO\",\"minutosDiferencia\":75}"));

        verify(bookingService).onHoldExpired(id);
        verify(affectedService).onItineraryCancelled(id);
        verify(affectedService).onItineraryRescheduled(id, "RETRASO", 75);
        assertThat(handler.eventTypes()).containsExactlyInAnyOrder("HoldExpired", "ItineraryCancelled",
                "ItineraryRescheduled");
    }

    @Test
    void sinIdentificadorOConOtroTipoNoHaceNada() throws Exception {
        handler.handle(evento("HoldExpired", "{}"));
        handler.handle(evento("ItineraryCancelled", "{}"));
        handler.handle(evento("ItineraryRescheduled", "{\"tipo\":\"RETRASO\"}"));
        handler.handle(evento("OtroEvento", "{\"itineraryId\":\"" + UUID.randomUUID() + "\"}"));

        verifyNoInteractions(bookingService);
        verify(affectedService, never()).onItineraryCancelled(any());
        verify(affectedService, never()).onItineraryRescheduled(any(), anyString(), anyLong());
    }
}
