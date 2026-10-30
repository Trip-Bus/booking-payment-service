package com.tripbus.booking.service;

import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.config.NoShowProperties;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.MyTripResponse;
import com.tripbus.booking.web.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * US-145: mis viajes próximos (salida hoy o después, hora de Colombia) y pasados (el resto). Solo las reservas que
 * llegaron a tener tiquete: CONFIRMADA, REPROGRAMADA y REEMBOLSADA, cada una con su estado visible.
 */
@Service
public class MyTripsService {

    /** Reservas que aparecen en mis viajes. */
    static final Set<BookingStatus> VISIBLES = EnumSet.of(BookingStatus.CONFIRMADA, BookingStatus.REPROGRAMADA,
            BookingStatus.REEMBOLSADA);
    static final int MAX_SIZE = 50;

    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final RescheduleService rescheduleService;
    private final BookingProperties properties;
    private final NoShowProperties noShow;
    private final Clock clock;

    public MyTripsService(BookingRepository bookingRepository, TicketRepository ticketRepository,
                          RescheduleService rescheduleService, BookingProperties properties,
                          NoShowProperties noShow, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.rescheduleService = rescheduleService;
        this.properties = properties;
        this.noShow = noShow;
        this.clock = clock;
    }

    public enum Scope { PROXIMOS, PASADOS }

    /** {@code scope}: proximos | pasados (400 INVALID_SCOPE si no). */
    public static Scope scope(String value) {
        try {
            return Scope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_SCOPE", "scope debe ser proximos o pasados");
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<MyTripResponse> list(UUID userId, Scope scope, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw ApiException.badRequest("INVALID_PAGE", "page debe ser >= 0 y size entre 1 y " + MAX_SIZE);
        }
        Instant now = clock.instant();
        Instant inicioDeHoy = now.atZone(TicketMailService.ZONA).toLocalDate().atStartOfDay(TicketMailService.ZONA)
                .toInstant();
        PageRequest pageable = PageRequest.of(page, size);
        Page<Booking> reservas = scope == Scope.PROXIMOS
                ? bookingRepository.findProximos(userId, VISIBLES, inicioDeHoy, pageable)
                : bookingRepository.findPasados(userId, VISIBLES, inicioDeHoy, pageable);
        Map<UUID, Ticket> tiquetes = reservas.isEmpty() ? Map.of()
                : ticketRepository.findByBookingIdIn(reservas.map(Booking::getId).toList()).stream()
                .collect(Collectors.toMap(Ticket::getBookingId, Function.identity()));
        return PageResponse.of(reservas, b -> toTrip(b, tiquetes.get(b.getId()), now));
    }

    private MyTripResponse toTrip(Booking b, Ticket ticket, Instant now) {
        String estadoTiquete = ticket != null ? ticket.getEstado() : null;
        return new MyTripResponse(b.getId(), b.getEstado().name(), b.getCanal().name(), b.getItineraryId(),
                b.getOrigen(), b.getDestino(), b.getFechaSalida(), b.getEmpresaNombre(), b.getSeatNumbers(),
                ticket != null ? ticket.getId() : null, estadoTiquete, b.getTotal(), b.getMoneda(),
                puedeReprogramar(b, estadoTiquete, now), puedeCambiarAsiento(b, estadoTiquete, now));
    }

    /**
     * Las mismas reglas de US-150 y US-153 sin consultar a otros servicios: EMITIDO con más de reschedule-cutoff para
     * la salida, o NO_SHOW (no de oferta) dentro de la ventana; en ambos casos sin pasar del límite de cambios y sin
     * otra reprogramación en curso.
     */
    boolean puedeReprogramar(Booking b, String estadoTiquete, Instant now) {
        if (b.getEstado() != BookingStatus.CONFIRMADA || b.getFechaSalida() == null) {
            return false;
        }
        boolean aTiempo;
        if (TicketService.EMITIDO.equals(estadoTiquete)) {
            aTiempo = b.getFechaSalida().isAfter(now.plus(properties.rescheduleCutoff()));
        } else if (TicketService.NO_SHOW.equals(estadoTiquete)) {
            aTiempo = Boolean.TRUE.equals(noShow.noShowRescheduleEnabled()) && b.getOfferId() == null
                    && !now.isAfter(b.getFechaSalida().plus(noShow.noShowRescheduleWindow()));
        } else {
            aTiempo = false;
        }
        return aTiempo && rescheduleService.reprogramaciones(b) < properties.maxReschedules()
                && !bookingRepository.existsByOrigenBookingIdAndEstadoIn(b.getId(), RescheduleService.VIVAS);
    }

    /** US-139: tiquete EMITIDO con más de 30 min para la salida. */
    static boolean puedeCambiarAsiento(Booking b, String estadoTiquete, Instant now) {
        return b.getEstado() == BookingStatus.CONFIRMADA && TicketService.EMITIDO.equals(estadoTiquete)
                && b.getFechaSalida() != null
                && b.getFechaSalida().isAfter(now.plus(TicketChangeService.PLAZO_CAMBIO_ASIENTO));
    }
}
