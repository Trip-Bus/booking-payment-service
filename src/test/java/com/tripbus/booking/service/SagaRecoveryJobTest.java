package com.tripbus.booking.service;

import com.tripbus.booking.common.redis.LeaderLock;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.PaymentStatus;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Recuperación de sagas (incluye US-150: anulaciones pendientes) y conciliación, con el líder simulado. */
class SagaRecoveryJobTest {

    private static final Instant AHORA = Instant.parse("2026-10-20T12:00:00Z");

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final RescheduleSaga rescheduleSaga = mock(RescheduleSaga.class);
    private final LeaderLock leaderLock = mock(LeaderLock.class);
    private SagaRecoveryJob job;

    @BeforeEach
    void preparar() {
        job = new SagaRecoveryJob(bookings, payments, tickets, paymentService, rescheduleSaga, leaderLock,
                Clock.fixed(AHORA, ZoneOffset.UTC));
        when(leaderLock.runIfLeader(anyString(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(2).run();
            return true;
        });
    }

    @Test
    void retomaPagosAprobadosCobrosSinRespuestaYAnulacionesPendientes() {
        UUID aprobada = UUID.randomUUID();
        UUID pago = UUID.randomUUID();
        UUID reprogramada = UUID.randomUUID();
        when(bookings.findIdsByEstadoAndActualizadoEnBefore(BookingStatus.PAGO_APROBADO, AHORA.minusSeconds(15)))
                .thenReturn(List.of(aprobada));
        when(payments.findIdsByEstadoAndCreadoEnBefore(PaymentStatus.PENDIENTE, AHORA.minus(Duration.ofMinutes(5))))
                .thenReturn(List.of(pago));
        when(bookings.findIdsConAnulacionPendiente(AHORA.minusSeconds(15))).thenReturn(List.of(reprogramada));

        job.recover();

        verify(paymentService).confirmAndIssue(aprobada);
        verify(paymentService).failPayment(pago, PaymentService.SIN_RESPUESTA);
        verify(rescheduleSaga).anularAsientosAnteriores(reprogramada);
    }

    @Test
    void laConciliacionCuentaLasReprogramadasComoReservasConTiquete() {
        when(bookings.countByEstado(BookingStatus.CONFIRMADA)).thenReturn(3L);
        when(bookings.countByEstado(BookingStatus.REPROGRAMADA)).thenReturn(1L);
        when(tickets.count()).thenReturn(4L, 5L);

        job.reconcile(); // cuadra
        job.reconcile(); // un tiquete de más: solo se registra en el log

        verify(bookings, org.mockito.Mockito.times(2)).countByEstado(BookingStatus.REPROGRAMADA);
    }
}
