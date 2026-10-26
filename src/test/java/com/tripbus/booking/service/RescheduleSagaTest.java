package com.tripbus.booking.service;

import com.tripbus.booking.client.RemoteErrors;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.events.BookingEvents;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-150: paso de la saga que retira el tiquete viejo y anula sus asientos. */
class RescheduleSagaTest {

    private static final Instant AHORA = Instant.parse("2026-10-20T12:00:00Z");

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final SagaLogger saga = mock(SagaLogger.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private RescheduleSaga rescheduleSaga;
    private Booking origen;
    private Booking nueva;
    private Ticket ticketViejo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        rescheduleSaga = new RescheduleSaga(bookings, tickets, seatInventory, saga, events, tx,
                Clock.fixed(AHORA, ZoneOffset.UTC));
        doAnswer(inv -> {
            inv.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any(Consumer.class));
        origen = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("12"),
                new BigDecimal("95000"), "COP", AHORA, AHORA);
        origen.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
        origen.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
        origen.cambiarEstado(BookingStatus.CONFIRMADA, AHORA);
        nueva = Booking.reprogramacion(origen, new Booking.SeatSelection(UUID.randomUUID(), UUID.randomUUID(), null,
                List.of("20"), AHORA), new BigDecimal("95000"), BigDecimal.ZERO, new BigDecimal("95000"), null, AHORA);
        ticketViejo = mock(Ticket.class);
        when(ticketViejo.getId()).thenReturn(UUID.randomUUID());
        when(ticketViejo.getEstado()).thenReturn("EMITIDO");
        when(bookings.findById(origen.getId())).thenReturn(Optional.of(origen));
        when(bookings.lockById(origen.getId())).thenReturn(Optional.of(origen));
        when(tickets.findByBookingId(origen.getId())).thenReturn(Optional.of(ticketViejo));
    }

    @Test
    void sigueVigenteSoloConLaOriginalConfirmadaYSuTiqueteEmitido() {
        assertThat(rescheduleSaga.sigueVigente(nueva)).isTrue();

        when(ticketViejo.getEstado()).thenReturn("USADO");
        assertThat(rescheduleSaga.sigueVigente(nueva)).isFalse();

        when(tickets.findByBookingId(origen.getId())).thenReturn(Optional.empty());
        assertThat(rescheduleSaga.sigueVigente(nueva)).isFalse();

        when(bookings.findById(origen.getId())).thenReturn(Optional.empty());
        assertThat(rescheduleSaga.sigueVigente(nueva)).isFalse();
    }

    @Test
    void retirarAnteriorReprogramaElTiqueteYLaReserva() {
        when(tickets.marcarReprogramado(ticketViejo.getId())).thenReturn(1);

        RescheduleSaga.Retiro retiro = rescheduleSaga.retirarAnterior(nueva);

        assertThat(retiro.origen()).isSameAs(origen);
        assertThat(retiro.ticketAnteriorId()).isEqualTo(ticketViejo.getId());
        assertThat(origen.getEstado()).isEqualTo(BookingStatus.REPROGRAMADA);
    }

    @Test
    void siElTiqueteViejoYaSeUsoNoSeRetiraNada() {
        when(tickets.marcarReprogramado(ticketViejo.getId())).thenReturn(0);

        assertThat(rescheduleSaga.retirarAnterior(nueva)).isNull();
        assertThat(origen.getEstado()).isEqualTo(BookingStatus.CONFIRMADA);

        when(tickets.findByBookingId(origen.getId())).thenReturn(Optional.empty());
        assertThat(rescheduleSaga.retirarAnterior(nueva)).isNull();
    }

    @Test
    void siLaOriginalYaNoEstaConfirmadaNoSeTocaElTiquete() {
        origen.cambiarEstado(BookingStatus.REPROGRAMADA, AHORA);

        assertThat(rescheduleSaga.retirarAnterior(nueva)).isNull();
        verify(tickets, never()).marcarReprogramado(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void registrarPublicaTicketRescheduledConLosDosLados() {
        Ticket nuevo = mock(Ticket.class);
        when(nuevo.getId()).thenReturn(UUID.randomUUID());

        rescheduleSaga.registrar(nueva, new RescheduleSaga.Retiro(origen, ticketViejo.getId()), nuevo);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publishAfterCommit(eq(BookingEvents.TICKET_RESCHEDULED), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("userId", origen.getUserId())
                .containsEntry("bookingIdAnterior", origen.getId())
                .containsEntry("bookingIdNuevo", nueva.getId())
                .containsEntry("ticketIdAnterior", ticketViejo.getId())
                .containsEntry("ticketIdNuevo", nuevo.getId())
                .containsEntry("itineraryIdAnterior", origen.getItineraryId())
                .containsEntry("itineraryIdNuevo", nueva.getItineraryId());
        verify(saga).log(eq(nueva.getId()), eq("REPROGRAMACION_APLICADA"), eq("OK"), anyMap());
        verify(saga).log(eq(origen.getId()), eq("REPROGRAMADA"), eq("OK"), anyMap());
    }

    private void reprogramada() {
        origen.cambiarEstado(BookingStatus.REPROGRAMADA, AHORA);
    }

    @Test
    void anularLosAsientosViejosLaDejaResuelta() {
        reprogramada();

        rescheduleSaga.anularAsientosAnteriores(origen.getId());

        verify(seatInventory).voidSale(origen.getItineraryId(), origen.getId(), List.of("12"), "REPROGRAMACION");
        assertThat(origen.getAnulacionResueltaEn()).isEqualTo(AHORA);
        verify(saga).log(eq(origen.getId()), eq(RescheduleSaga.PASO_ANULACION), eq("OK"), anyMap());
    }

    @Test
    void siSeatInventoryNoRespondeQuedaParaReintentar() {
        reprogramada();
        doThrow(new RemoteErrors.RemoteUnavailableException("caído", null))
                .when(seatInventory).voidSale(any(), any(), any(), any());

        rescheduleSaga.anularAsientosAnteriores(origen.getId());

        assertThat(origen.getAnulacionResueltaEn()).isNull();
        verify(saga).log(eq(origen.getId()), eq(RescheduleSaga.PASO_ANULACION), eq("REINTENTO"), anyMap());
    }

    @Test
    void siSeatInventoryLaRechazaQuedaResueltaConError() {
        reprogramada();
        doThrow(new ApiException(HttpStatus.CONFLICT, "SEAT_NOT_OWNED", "no es de la reserva"))
                .when(seatInventory).voidSale(any(), any(), any(), any());

        rescheduleSaga.anularAsientosAnteriores(origen.getId());

        assertThat(origen.getAnulacionResueltaEn()).isEqualTo(AHORA);
        verify(saga).log(eq(origen.getId()), eq(RescheduleSaga.PASO_ANULACION), eq("ERROR"), anyMap());
    }

    @Test
    void noAnulaSiNoHaceFaltaOYaSeResolvio() {
        rescheduleSaga.anularAsientosAnteriores(UUID.randomUUID()); // no existe
        rescheduleSaga.anularAsientosAnteriores(origen.getId());    // sigue CONFIRMADA
        reprogramada();
        origen.marcarAnulacionResuelta(AHORA);
        rescheduleSaga.anularAsientosAnteriores(origen.getId());    // ya resuelta

        verify(seatInventory, never()).voidSale(any(), any(), any(), any());
    }

    @Test
    void siOtraPasadaLaResolvioMientrasTantoNoSeRegistraDosVeces() {
        reprogramada();
        doAnswer(inv -> {
            origen.marcarAnulacionResuelta(AHORA.minusSeconds(1));
            return null;
        }).when(seatInventory).voidSale(any(), any(), any(), any());

        rescheduleSaga.anularAsientosAnteriores(origen.getId());

        assertThat(origen.getAnulacionResueltaEn()).isEqualTo(AHORA.minusSeconds(1));
        verify(saga, never()).log(any(), eq(RescheduleSaga.PASO_ANULACION), any(), anyMap());
    }

    @Test
    void devolverLosAsientosNuevosNuncaLanza() {
        rescheduleSaga.devolverAsientosNuevos(nueva);
        verify(seatInventory).voidSale(nueva.getItineraryId(), nueva.getId(), List.of("20"), "REEMBOLSO");

        doThrow(new RemoteErrors.RemoteUnavailableException("caído", null))
                .when(seatInventory).voidSale(any(), any(), any(), any());
        rescheduleSaga.devolverAsientosNuevos(nueva);
    }
}
