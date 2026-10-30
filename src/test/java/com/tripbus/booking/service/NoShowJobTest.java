package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.common.redis.LeaderLock;
import com.tripbus.booking.config.NoShowProperties;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-153 (job de no-show) y US-145 (relleno de los datos del viaje), con los repositorios simulados. */
class NoShowJobTest {

    private static final Instant AHORA = Instant.parse("2026-10-30T15:00:00Z");

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final LeaderLock lock = mock(LeaderLock.class);
    private final EventPublisher events = mock(EventPublisher.class);

    private NoShowJob job() {
        when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        return new NoShowJob(tickets, bookings, mock(SagaLogger.class), events, tx, lock,
                new NoShowProperties(Duration.ofMinutes(45), null, null, null), Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void correEnUnSoloNodoConElPlazoConfigurado() {
        NoShowJob job = job();
        job.run();
        verify(lock).runIfLeader(eq("booking-no-show"), any(), any());

        when(tickets.findIdsParaNoShow(AHORA.minus(Duration.ofMinutes(45)))).thenReturn(List.of());
        assertThat(job.runNow()).isZero();
    }

    @Test
    void siElTiqueteYaNoExisteSeUsoOFallaNoSeCuentaYSigueConLosDemas() {
        NoShowJob job = job();
        UUID inexistente = UUID.randomUUID();
        UUID usado = UUID.randomUUID();
        UUID falla = UUID.randomUUID();
        when(tickets.findIdsParaNoShow(any())).thenReturn(List.of(inexistente, usado, falla));
        when(tickets.findById(inexistente)).thenReturn(Optional.empty());
        when(tickets.findById(usado)).thenReturn(Optional.of(mock(Ticket.class)));
        when(tickets.marcarNoShow(usado)).thenReturn(0);
        when(tickets.findById(falla)).thenThrow(new IllegalStateException("BD caída"));

        assertThat(job.runNow()).isZero();
        verify(events, never()).publishAfterCommit(anyString(), any());
    }

    @Test
    void elRellenoSeDetieneTrasTresFallosSeguidosYSiEstaApagadoNoCorre() {
        CatalogClient catalog = mock(CatalogClient.class);
        UUID conDatos = UUID.randomUUID();
        UUID sinSalida = UUID.randomUUID();
        when(bookings.findItinerariosSinViaje()).thenReturn(List.of(sinSalida, conDatos, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        when(catalog.itinerary(sinSalida)).thenReturn(new CatalogClient.ItinerarySummary(sinSalida, "A", "B", null,
                null, "E", "P"));
        when(catalog.itinerary(conDatos)).thenReturn(new CatalogClient.ItinerarySummary(conDatos, "A", "B", AHORA,
                null, "E", "P"));
        when(tx.execute(any())).thenReturn(2).thenReturn(null);

        TripDataBackfill backfill = new TripDataBackfill(bookings, catalog, tx, true);
        assertThat(backfill.run()).isEqualTo(2);
        verify(catalog, times(5)).itinerary(any()); // 1 fallo, 1 bien y 3 fallos seguidos

        TripDataBackfill apagado = new TripDataBackfill(bookings, catalog, tx, false);
        apagado.onReady();
        verify(bookings, times(1)).findItinerariosSinViaje();
        backfill.onReady();
        verify(bookings, times(2)).findItinerariosSinViaje();
    }
}
