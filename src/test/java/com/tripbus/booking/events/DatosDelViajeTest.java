package com.tripbus.booking.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tripbus.booking.common.events.EventEnvelope;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.service.AffectedBookingService;
import com.tripbus.booking.service.BookingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** US-145: la salida que trae ItineraryRescheduled y los datos del viaje que guarda la reserva. */
class DatosDelViajeTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void laFechaDelEventoPuedeVenirComoTextoOSegundos() {
        Instant salida = Instant.parse("2026-11-02T13:30:00Z");

        assertThat(BookingEventsHandler.instante(JSON.textNode("2026-11-02T13:30:00Z"))).isEqualTo(salida);
        assertThat(BookingEventsHandler.instante(JSON.numberNode(new BigDecimal("1793626200.5"))))
                .isEqualTo(salida.plusMillis(500));
        assertThat(BookingEventsHandler.instante(JSON.textNode("mañana"))).isNull();
        assertThat(BookingEventsHandler.instante(JSON.missingNode())).isNull();
    }

    @Test
    void itineraryRescheduledActualizaLaSalidaDeLasReservas() throws Exception {
        BookingService bookingService = mock(BookingService.class);
        AffectedBookingService affected = mock(AffectedBookingService.class);
        UUID itinerario = UUID.randomUUID();
        var payload = new ObjectMapper().readTree("{\"itineraryId\":\"" + itinerario + "\",\"tipo\":\"RETRASO\","
                + "\"minutosDiferencia\":90,\"fechaSalida\":\"2026-11-02T13:30:00Z\"}");

        new BookingEventsHandler(bookingService, affected).handle(new EventEnvelope("e1", BookingEvents.ITINERARY_RESCHEDULED,
                "catalog", Instant.now(), payload));

        verify(bookingService).onItineraryRescheduled(itinerario, Instant.parse("2026-11-02T13:30:00Z"));
        verify(affected).onItineraryRescheduled(itinerario, "RETRASO", 90);
    }

    @Test
    void losDatosNulosNoBorranLosQueYaTieneLaReserva() {
        Booking b = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("1"),
                BigDecimal.TEN, "COP", null, Instant.now());
        Instant salida = Instant.now();
        b.asignarViaje(new Booking.Viaje("Cali", "Pasto", salida, "Bolivariano"));

        b.asignarViaje(new Booking.Viaje(null, null, null, null));
        b.asignarViaje(null);

        assertThat(b.getOrigen()).isEqualTo("Cali");
        assertThat(b.getDestino()).isEqualTo("Pasto");
        assertThat(b.getFechaSalida()).isEqualTo(salida);
        assertThat(b.getEmpresaNombre()).isEqualTo("Bolivariano");
        assertThat(b.getCanal()).isEqualTo(Booking.Canal.APP);
        assertThat(b.getVendidoPor()).isNull();
    }
}
