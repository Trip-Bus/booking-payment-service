package com.tripbus.booking.service;

import com.tripbus.booking.common.redis.LeaderLock;
import com.tripbus.booking.repository.TicketRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * US-033: cada minuto reintenta enviar por correo los tiquetes no notificados con menos de 5 intentos
 * fallidos. Corre en un único nodo (LeaderLock).
 */
@Component
public class TicketMailRetryJob {

    /** Margen para no competir con el envío inmediato tras el commit de un tiquete recién emitido. */
    static final Duration GRACIA_PRIMER_ENVIO = Duration.ofMinutes(2);

    private final TicketRepository ticketRepository;
    private final TicketMailService mailService;
    private final LeaderLock leaderLock;
    private final Clock clock;

    public TicketMailRetryJob(TicketRepository ticketRepository, TicketMailService mailService, LeaderLock leaderLock,
                              Clock clock) {
        this.ticketRepository = ticketRepository;
        this.mailService = mailService;
        this.leaderLock = leaderLock;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.ticket-mail-retry-ms:60000}",
            initialDelayString = "${tripbus.jobs.ticket-mail-retry-initial-ms:30000}")
    public void retry() {
        if (mailService.enabled()) {
            leaderLock.runIfLeader("booking-ticket-mail-retry", Duration.ofSeconds(55), this::retryNow);
        }
    }

    /** Devuelve cuántos correos quedaron enviados en esta pasada. */
    public int retryNow() {
        if (!mailService.enabled()) {
            return 0;
        }
        int enviados = 0;
        for (UUID ticketId : ticketRepository.findIdsPendientesDeNotificar(TicketMailService.MAX_INTENTOS,
                clock.instant().minus(GRACIA_PRIMER_ENVIO))) {
            if (mailService.send(ticketId)) {
                enviados++;
            }
        }
        return enviados;
    }
}
