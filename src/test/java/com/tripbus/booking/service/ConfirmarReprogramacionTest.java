package com.tripbus.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.payment.WebhookSigner;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.RefundRepository;
import com.tripbus.booking.repository.WebhookLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-150: el tiquete viejo se usa para abordar justo entre la validación previa y la transacción que aplica la
 * reprogramación. La reserva nueva no emite tiquete: se devuelven sus asientos y se reembolsa.
 */
class ConfirmarReprogramacionTest {

    private static final Instant AHORA = Instant.parse("2026-10-20T12:00:00Z");

    @Test
    void siElTiqueteViejoSeUsaEnLaCarreraLaReprogramacionSeReembolsaYSeDevuelvenLosAsientos() {
        BookingRepository bookings = mock(BookingRepository.class);
        PaymentRepository payments = mock(PaymentRepository.class);
        SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
        TicketService ticketService = mock(TicketService.class);
        RescheduleSaga rescheduleSaga = mock(RescheduleSaga.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        PaymentService service = new PaymentService(bookings, payments, mock(RefundRepository.class),
                mock(WebhookLogRepository.class), seatInventory, mock(PaymentGateway.class), mock(WebhookSigner.class),
                ticketService, mock(TicketMailService.class), rescheduleSaga, mock(SagaLogger.class),
                mock(EventPublisher.class), tx, new ObjectMapper(), Clock.fixed(AHORA, ZoneOffset.UTC));

        Booking origen = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("12"),
                new BigDecimal("95000"), "COP", AHORA, AHORA);
        Booking nueva = Booking.reprogramacion(origen, new Booking.SeatSelection(UUID.randomUUID(), UUID.randomUUID(),
                null, List.of("20"), AHORA), new BigDecimal("80000"), BigDecimal.ZERO, new BigDecimal("95000"), null,
                AHORA);
        when(bookings.findById(nueva.getId())).thenReturn(Optional.of(nueva));
        when(bookings.lockById(nueva.getId())).thenReturn(Optional.of(nueva));
        when(rescheduleSaga.sigueVigente(nueva)).thenReturn(true);
        when(rescheduleSaga.retirarAnterior(nueva)).thenReturn(null);

        service.confirmAndIssue(nueva.getId());

        verify(seatInventory).confirmSale(nueva.getHoldId(), nueva.getId());
        verify(ticketService, never()).issue(any());
        verify(rescheduleSaga).devolverAsientosNuevos(nueva);
        verify(rescheduleSaga, never()).anularAsientosAnteriores(any());
        assertThat(nueva.getEstado()).isEqualTo(BookingStatus.REEMBOLSADA);
    }
}
