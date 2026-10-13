package com.tripbus.booking.service;

import com.tripbus.booking.common.redis.LeaderLock;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.PaymentStatus;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Saga con estado persistido: si el servicio se cae a mitad de la compra, al reiniciar
 * este job retoma desde el último paso confirmado. Incluye la conciliación periódica
 * (cada 15 min) entre reservas, pagos y tiquetes. Ambos corren en un único nodo.
 */
@Component
public class SagaRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryJob.class);

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final PaymentService paymentService;
    private final LeaderLock leaderLock;
    private final Clock clock;

    public SagaRecoveryJob(BookingRepository bookingRepository, PaymentRepository paymentRepository,
                           TicketRepository ticketRepository, PaymentService paymentService, LeaderLock leaderLock,
                           Clock clock) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.ticketRepository = ticketRepository;
        this.paymentService = paymentService;
        this.leaderLock = leaderLock;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.saga-recovery-ms:30000}", initialDelay = 20000)
    public void recover() {
        leaderLock.runIfLeader("booking-saga-recovery", Duration.ofSeconds(25), this::recoverNow);
    }

    public void recoverNow() {
        Instant now = clock.instant();
        // Pagos aprobados cuyo asiento aún no se confirmó (p. ej. Seat Inventory estaba caído).
        for (UUID bookingId : bookingRepository.findIdsByEstadoAndActualizadoEnBefore(
                BookingStatus.PAGO_APROBADO, now.minusSeconds(15))) {
            log.info("Retomando la saga de la reserva {}", bookingId);
            paymentService.confirmAndIssue(bookingId);
        }
        // Cobros sin respuesta de la pasarela por más de 5 minutos.
        for (UUID paymentId : paymentRepository.findIdsByEstadoAndCreadoEnBefore(
                PaymentStatus.PENDIENTE, now.minus(Duration.ofMinutes(5)))) {
            log.warn("El pago {} no recibió respuesta de la pasarela; se marca como fallido", paymentId);
            paymentService.failPayment(paymentId, PaymentService.SIN_RESPUESTA);
        }
    }

    /** Conciliación entre bookings, payments y tickets (doc: job cada 15 min). */
    @Scheduled(fixedDelayString = "${tripbus.jobs.reconciliation-ms:900000}", initialDelay = 60000)
    public void reconcile() {
        leaderLock.runIfLeader("booking-reconciliation", Duration.ofMinutes(14), () -> {
            long confirmadas = bookingRepository.countByEstado(BookingStatus.CONFIRMADA);
            long tickets = ticketRepository.count();
            long pendientesConfirmar = bookingRepository.countByEstado(BookingStatus.PAGO_APROBADO);
            if (confirmadas != tickets) {
                log.error("CONCILIACION: {} reservas confirmadas pero {} tiquetes emitidos", confirmadas, tickets);
            }
            log.info("Conciliación: confirmadas={}, tiquetes={}, pagos aprobados sin asiento confirmado={}",
                    confirmadas, tickets, pendientesConfirmar);
        });
    }
}
