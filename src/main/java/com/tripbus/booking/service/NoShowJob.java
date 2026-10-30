package com.tripbus.booking.service;

import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.common.redis.LeaderLock;
import com.tripbus.booking.config.NoShowProperties;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.events.BookingEvents;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * US-153: cada 5 min (en un solo nodo, {@link LeaderLock}) los tiquetes EMITIDO de viajes que salieron hace más de
 * {@code tripbus.booking.no-show-after} pasan a NO_SHOW y se publica TicketNoShow. No hay reembolso. El cambio es un
 * UPDATE condicional (compite con el abordaje): si el QR se validó entre tanto, el tiquete queda USADO.
 */
@Component
public class NoShowJob {

    private static final Logger log = LoggerFactory.getLogger(NoShowJob.class);

    private final TicketRepository ticketRepository;
    private final BookingRepository bookingRepository;
    private final SagaLogger saga;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final LeaderLock leaderLock;
    private final NoShowProperties properties;
    private final Clock clock;

    @SuppressWarnings("java:S107") // dependencias del job, inyectadas por Spring
    public NoShowJob(TicketRepository ticketRepository, BookingRepository bookingRepository, SagaLogger saga,
                     EventPublisher events, TransactionTemplate tx, LeaderLock leaderLock,
                     NoShowProperties properties, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.bookingRepository = bookingRepository;
        this.saga = saga;
        this.events = events;
        this.tx = tx;
        this.leaderLock = leaderLock;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.no-show-ms:300000}", initialDelay = 60000)
    public void run() {
        leaderLock.runIfLeader("booking-no-show", Duration.ofMinutes(4), this::runNow);
    }

    /** Devuelve cuántos tiquetes pasaron a NO_SHOW. */
    public int runNow() {
        int marcados = 0;
        for (UUID ticketId : ticketRepository.findIdsParaNoShow(clock.instant().minus(properties.noShowAfter()))) {
            try {
                if (Boolean.TRUE.equals(tx.execute(s -> marcar(ticketId)))) {
                    marcados++;
                }
            } catch (RuntimeException e) {
                // Se reintenta en la próxima pasada.
                log.warn("No se pudo marcar como no-show el tiquete {}: {}", ticketId, e.getMessage());
            }
        }
        if (marcados > 0) {
            log.info("{} tiquetes pasaron a NO_SHOW", marcados);
        }
        return marcados;
    }

    private boolean marcar(UUID ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null || ticketRepository.marcarNoShow(ticketId) != 1) {
            return false;
        }
        Booking booking = bookingRepository.findById(ticket.getBookingId()).orElseThrow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", booking.getUserId());
        payload.put("bookingId", booking.getId());
        payload.put("ticketId", ticketId);
        payload.put("itineraryId", booking.getItineraryId());
        saga.log(booking.getId(), "NO_SHOW", "OK", payload);
        events.publishAfterCommit(BookingEvents.TICKET_NO_SHOW, payload);
        return true;
    }
}
