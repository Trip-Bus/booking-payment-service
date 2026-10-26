package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.RemoteErrors;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.common.redis.LeaderLock;
import com.tripbus.booking.domain.AffectedBooking;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.events.BookingEvents;
import com.tripbus.booking.repository.AffectedBookingRepository;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.BookingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * US-155: cada 5 min (en un solo nodo, {@link LeaderLock}) decide por el pasajero las reservas afectadas cuyo plazo
 * venció: REPROGRAMACION o RETRASO → ACEPTO_CAMBIO; CANCELACION → se reubica gratis en el siguiente viaje de la misma
 * ruta y empresa con cupo para todos, que salga después del original y dentro de los 3 días siguientes (retiene los
 * primeros asientos disponibles a nombre del pasajero); sin cupo → reembolso. También reintenta las anulaciones de
 * asientos pendientes de los reembolsos.
 */
@Component
public class AffectedBookingJob {

    private static final Logger log = LoggerFactory.getLogger(AffectedBookingJob.class);

    static final Duration VENTANA_REUBICACION = Duration.ofDays(3);

    private final AffectedBookingRepository affectedRepository;
    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final CatalogClient catalog;
    private final SeatInventoryClient seatInventory;
    private final AffectedBookingService affectedService;
    private final AffectedRefundService refundService;
    private final RescheduleService rescheduleService;
    private final AffectedMailService mail;
    private final EventPublisher events;
    private final LeaderLock leaderLock;
    private final Clock clock;

    @SuppressWarnings("java:S107") // dependencias de la decisión automática, inyectadas por Spring
    public AffectedBookingJob(AffectedBookingRepository affectedRepository, BookingRepository bookingRepository,
                              TicketRepository ticketRepository, CatalogClient catalog,
                              SeatInventoryClient seatInventory, AffectedBookingService affectedService,
                              AffectedRefundService refundService, RescheduleService rescheduleService,
                              AffectedMailService mail, EventPublisher events, LeaderLock leaderLock, Clock clock) {
        this.affectedRepository = affectedRepository;
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.catalog = catalog;
        this.seatInventory = seatInventory;
        this.affectedService = affectedService;
        this.refundService = refundService;
        this.rescheduleService = rescheduleService;
        this.mail = mail;
        this.events = events;
        this.leaderLock = leaderLock;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.affected-ms:300000}", initialDelay = 45000)
    public void run() {
        leaderLock.runIfLeader("booking-affected-decisions", Duration.ofMinutes(4), this::runNow);
    }

    public void runNow() {
        Instant now = clock.instant();
        for (UUID bookingId : affectedRepository.findIdsConPlazoVencido(now)) {
            try {
                decidirPorSistema(bookingId);
            } catch (RuntimeException e) {
                // Se reintenta en la próxima pasada (p. ej. catalog o seat-inventory no respondieron).
                log.warn("No se pudo decidir la reserva afectada {}: {}", bookingId, e.getMessage());
            }
        }
        for (UUID bookingId : affectedRepository.findIdsConAnulacionPendiente(now.minusSeconds(15))) {
            refundService.anularAsientos(bookingId);
        }
    }

    void decidirPorSistema(UUID bookingId) {
        AffectedBooking afectada = affectedRepository.findById(bookingId).orElse(null);
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (afectada == null || booking == null || !afectada.pendiente()) {
            return;
        }
        if (cerrarSiYaNoAplica(afectada, booking)) {
            return;
        }
        if (afectada.getCausa() != AffectedBooking.Causa.CANCELACION) {
            affectedService.aceptar(bookingId, AffectedBooking.DecididoPor.SISTEMA);
        } else {
            Optional<BookingResponse> nueva = reubicar(booking);
            if (nueva.isPresent()) {
                affectedService.registrarNuevaReserva(bookingId, nueva.get(), AffectedBooking.Estado.REUBICADA,
                        AffectedBooking.DecididoPor.SISTEMA);
                publicarReubicada(booking, nueva.get());
            } else {
                log.info("Sin cupo en los {} días siguientes para la reserva {}: se reembolsa",
                        VENTANA_REUBICACION.toDays(), bookingId);
                refundService.reembolsar(bookingId, AffectedBooking.DecididoPor.SISTEMA);
            }
        }
        affectedRepository.findById(bookingId).ifPresent(a -> mail.notificarDecisionDelSistema(booking, a));
    }

    /**
     * El pasajero ya resolvió por su cuenta: reprogramó con US-150 (REPROGRAMADA) o abordó con el horario nuevo
     * (ACEPTO_CAMBIO). Se registra como su decisión y no se hace nada más.
     */
    private boolean cerrarSiYaNoAplica(AffectedBooking afectada, Booking booking) {
        String estadoTiquete = ticketRepository.findByBookingId(booking.getId()).map(Ticket::getEstado).orElse(null);
        if (booking.getEstado() == BookingStatus.CONFIRMADA && TicketService.EMITIDO.equals(estadoTiquete)) {
            return false;
        }
        AffectedBooking.Estado resultado = booking.getEstado() == BookingStatus.REPROGRAMADA
                ? AffectedBooking.Estado.REPROGRAMADA : AffectedBooking.Estado.ACEPTO_CAMBIO;
        affectedService.cerrar(afectada.getBookingId(), resultado);
        return true;
    }

    /**
     * Siguiente viaje de la misma ruta y empresa con cupo para todos los pasajeros, después del original y dentro de
     * los 3 días siguientes: retiene los primeros asientos disponibles y confirma con el flujo gratis. Vacío si no hay
     * cupo; si catalog o seat-inventory no responden lanza la excepción (no se concluye "sin cupo").
     */
    Optional<BookingResponse> reubicar(Booking booking) {
        CatalogClient.ItinerarySummary original = catalog.itinerary(booking.getItineraryId());
        if (original == null || original.fechaSalida() == null) {
            throw new RemoteErrors.RemoteUnavailableException("Catalog no devolvió el viaje original", null);
        }
        Instant salida = original.fechaSalida();
        Instant desde = salida.isAfter(clock.instant()) ? salida : clock.instant();
        Instant hasta = salida.plus(VENTANA_REUBICACION);
        UUID empresa = original.companyId() != null ? original.companyId() : booking.getCompanyId();
        int pasajeros = booking.getSeatNumbers().size();
        BookingViewer dueno = new BookingViewer(booking.getUserId(), false, false, null);

        Set<UUID> intentados = new HashSet<>();
        intentados.add(booking.getItineraryId());
        LocalDate ultimoDia = hasta.atZone(TicketMailService.ZONA).toLocalDate();
        for (LocalDate dia = desde.atZone(TicketMailService.ZONA).toLocalDate(); !dia.isAfter(ultimoDia);
             dia = dia.plusDays(1)) {
            List<CatalogClient.TripOption> opciones = catalog.search(original.origen(), original.destino(), dia)
                    .stream()
                    .filter(t -> intentados.add(t.itineraryId()))
                    .filter(t -> t.fechaSalida() != null && t.fechaSalida().isAfter(desde) && !t.fechaSalida().isAfter(hasta))
                    .filter(t -> t.asientosDisponibles() == null || t.asientosDisponibles() >= pasajeros)
                    .sorted(Comparator.comparing(CatalogClient.TripOption::fechaSalida))
                    .toList();
            for (CatalogClient.TripOption opcion : opciones) {
                Optional<BookingResponse> nueva = intentar(booking, opcion.itineraryId(), empresa, pasajeros, dueno);
                if (nueva.isPresent()) {
                    return nueva;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<BookingResponse> intentar(Booking booking, UUID itineraryId, UUID empresa, int pasajeros,
                                               BookingViewer dueno) {
        CatalogClient.ItinerarySummary viaje = catalog.itinerary(itineraryId);
        if (viaje == null || "CANCELADO".equals(viaje.estado()) || empresa == null
                || !empresa.equals(viaje.companyId())) {
            return Optional.empty();
        }
        List<String> libres = seatInventory.availableSeats(itineraryId);
        if (libres.size() < pasajeros) {
            return Optional.empty();
        }
        SeatInventoryClient.Hold hold;
        try {
            hold = seatInventory.createHold(itineraryId, libres.subList(0, pasajeros), booking.getUserId());
        } catch (ApiException e) {
            return Optional.empty(); // otro pasajero los tomó entre tanto: siguiente viaje
        }
        try {
            BookingResponse nueva = rescheduleService.rescheduleAffected(booking, hold.holdId(), dueno).booking();
            if (BookingStatus.CONFIRMADA.name().equals(nueva.estado())
                    || BookingStatus.PAGO_APROBADO.name().equals(nueva.estado())) {
                return Optional.of(nueva);
            }
            return Optional.empty();
        } catch (ApiException e) {
            log.warn("No se pudo reubicar la reserva {} en el viaje {} ({})", booking.getId(), itineraryId, e.getCode());
            liberar(hold.holdId());
            return Optional.empty();
        }
    }

    private void liberar(UUID holdId) {
        try {
            seatInventory.releaseHold(holdId, "REUBICACION_FALLIDA");
        } catch (RuntimeException e) {
            log.warn("No se pudo liberar la retención {}: {}", holdId, e.getMessage());
        }
    }

    private void publicarReubicada(Booking anterior, BookingResponse nueva) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", anterior.getUserId());
        payload.put("bookingIdAnterior", anterior.getId());
        payload.put("bookingIdNuevo", nueva.bookingId());
        payload.put("ticketIdAnterior", ticketRepository.findByBookingId(anterior.getId()).map(Ticket::getId).orElse(null));
        payload.put("ticketIdNuevo", nueva.ticketId());
        payload.put("itineraryIdAnterior", anterior.getItineraryId());
        payload.put("itineraryIdNuevo", nueva.itineraryId());
        payload.put("seatNumbers", nueva.seatNumbers());
        events.publishAfterCommit(BookingEvents.BOOKING_RELOCATED, payload);
    }
}
