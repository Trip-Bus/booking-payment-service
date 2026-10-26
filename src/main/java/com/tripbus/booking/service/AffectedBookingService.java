package com.tripbus.booking.service;

import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.config.AffectedProperties;
import com.tripbus.booking.domain.AffectedBooking;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.events.BookingEvents;
import com.tripbus.booking.repository.AffectedBookingRepository;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.AffectedBookingResponse;
import com.tripbus.booking.web.dto.BookingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * US-154: reservas afectadas por un cambio de la empresa. Al llegar ItineraryCancelled o ItineraryRescheduled
 * (REPROGRAMACION, o RETRASO de al menos tripbus.booking.affected-delay-min) cada reserva CONFIRMADA del viaje con
 * tiquete EMITIDO queda PENDIENTE con un plazo para decidir; se le avisa por correo y con BookingAffected. El dueño
 * elige: aceptar el nuevo horario (solo si no se canceló), reprogramar gratis o reembolso. Al vencer el plazo decide
 * {@link AffectedBookingJob}.
 */
@Service
public class AffectedBookingService {

    private static final Logger log = LoggerFactory.getLogger(AffectedBookingService.class);

    static final String TIPO_REPROGRAMACION = "REPROGRAMACION";
    static final String TIPO_RETRASO = "RETRASO";
    private static final String OK = "OK";
    private static final Set<BookingStatus> NUEVA_VIGENTE = Set.of(BookingStatus.CONFIRMADA,
            BookingStatus.PAGO_APROBADO);

    private final AffectedBookingRepository affectedRepository;
    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final RescheduleService rescheduleService;
    private final AffectedRefundService refundService;
    private final AffectedMailService mail;
    private final SagaLogger saga;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final AffectedProperties properties;
    private final Clock clock;

    @SuppressWarnings("java:S107") // dependencias de las reservas afectadas, inyectadas por Spring
    public AffectedBookingService(AffectedBookingRepository affectedRepository, BookingRepository bookingRepository,
                                  TicketRepository ticketRepository, RescheduleService rescheduleService,
                                  AffectedRefundService refundService, AffectedMailService mail, SagaLogger saga,
                                  EventPublisher events, TransactionTemplate tx, AffectedProperties properties,
                                  Clock clock) {
        this.affectedRepository = affectedRepository;
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.rescheduleService = rescheduleService;
        this.refundService = refundService;
        this.mail = mail;
        this.saga = saga;
        this.events = events;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ eventos de catalog

    /** ItineraryCancelled. Corre en la transacción del consumidor. Devuelve cuántas reservas quedaron afectadas. */
    public int onItineraryCancelled(UUID itineraryId) {
        return registrar(itineraryId, AffectedBooking.Causa.CANCELACION);
    }

    /**
     * ItineraryRescheduled: tipo REPROGRAMACION afecta siempre; RETRASO solo si es de al menos el umbral (un retraso
     * menor no afecta a nadie). Devuelve cuántas reservas quedaron afectadas.
     */
    public int onItineraryRescheduled(UUID itineraryId, String tipo, long minutosDiferencia) {
        if (TIPO_REPROGRAMACION.equals(tipo)) {
            return registrar(itineraryId, AffectedBooking.Causa.REPROGRAMACION);
        }
        if (TIPO_RETRASO.equals(tipo) && minutosDiferencia >= properties.affectedDelayMin()) {
            return registrar(itineraryId, AffectedBooking.Causa.RETRASO);
        }
        log.info("Cambio {} de {} min en el viaje {}: no afecta reservas", tipo, minutosDiferencia, itineraryId);
        return 0;
    }

    private int registrar(UUID itineraryId, AffectedBooking.Causa causa) {
        Instant now = clock.instant();
        Instant plazo = now.plus(properties.affectedDecisionWindow());
        int afectadas = 0;
        for (Booking booking : bookingRepository.findConfirmadasConTiqueteEmitido(itineraryId)) {
            AffectedBooking afectada = affectedRepository.findById(booking.getId()).orElse(null);
            if (afectada != null && afectada.pendiente() && afectada.getCausa() == AffectedBooking.Causa.CANCELACION) {
                continue; // ya está pendiente por la cancelación: un cambio posterior no la reemplaza
            }
            if (afectada == null) {
                afectada = affectedRepository.save(new AffectedBooking(booking.getId(), itineraryId, causa, plazo, now));
            } else {
                afectada.reabrir(causa, plazo, now);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", booking.getUserId());
            payload.put("bookingId", booking.getId());
            payload.put("ticketId", ticketId(booking.getId()));
            payload.put("itineraryId", itineraryId);
            payload.put("causa", causa.name());
            payload.put("plazoDecision", plazo);
            payload.put("opciones", afectada.opciones().stream().map(Enum::name).toList());
            saga.log(booking.getId(), "RESERVA_AFECTADA", "PENDIENTE", payload);
            events.publishAfterCommit(BookingEvents.BOOKING_AFFECTED, payload);
            mail.notificarAfectada(booking, afectada);
            afectadas++;
        }
        log.info("Viaje {} ({}): {} reservas afectadas", itineraryId, causa, afectadas);
        return afectadas;
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public List<AffectedBookingResponse> mine(UUID userId) {
        return affectedRepository.findPendientesDelUsuario(userId).stream().map(this::toResponse).toList();
    }

    /** Detalle: el dueño, ADMIN u OPERADOR de la empresa (como GET /bookings/{id}). */
    @Transactional(readOnly = true)
    public AffectedBookingResponse get(UUID bookingId, BookingViewer viewer) {
        bookingRepository.findById(bookingId).filter(viewer::canSee)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "La reserva no existe"));
        return toResponse(afectada(bookingId));
    }

    // ------------------------------------------------------------------ decisiones del dueño

    /** Aceptar el nuevo horario (solo REPROGRAMACION o RETRASO): el tiquete sigue válido. */
    public AffectedBookingResponse accept(UUID bookingId, BookingViewer viewer) {
        AffectedBooking afectada = paraDecidir(bookingId, viewer);
        if (afectada.getCausa() == AffectedBooking.Causa.CANCELACION) {
            throw ApiException.conflict("OPTION_NOT_AVAILABLE", "El viaje se canceló: no hay un nuevo horario que aceptar");
        }
        aceptar(bookingId, AffectedBooking.DecididoPor.PASAJERO);
        return detalle(bookingId);
    }

    /** Reembolso del total pagado (idempotente: repetirlo devuelve el mismo resultado sin reembolsar otra vez). */
    public AffectedBookingResponse refund(UUID bookingId, BookingViewer viewer) {
        AffectedBooking afectada = propia(bookingId, viewer);
        if (afectada.getEstado() != AffectedBooking.Estado.REEMBOLSADA) {
            validarPendiente(afectada);
            refundService.reembolsar(bookingId, AffectedBooking.DecididoPor.PASAJERO);
        }
        return detalle(bookingId);
    }

    /** Reprogramar gratis con la retención {holdId} (flujo de US-150 sin diferencia, sin límite ni plazo de 3 h). */
    public AffectedBookingResponse reschedule(UUID bookingId, UUID holdId, BookingViewer viewer) {
        paraDecidir(bookingId, viewer);
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        BookingResponse nueva = rescheduleService.rescheduleAffected(booking, holdId, viewer).booking();
        registrarNuevaReserva(bookingId, nueva, AffectedBooking.Estado.REPROGRAMADA,
                AffectedBooking.DecididoPor.PASAJERO);
        return detalle(bookingId);
    }

    /**
     * La reserva nueva ya existe: si quedó confirmada (o lista para confirmarse), la afectada queda decidida. Si la
     * reserva nueva no se pudo confirmar (p. ej. el asiento se perdió) la afectada sigue PENDIENTE: 409.
     */
    void registrarNuevaReserva(UUID bookingId, BookingResponse nueva, AffectedBooking.Estado resultado,
                               AffectedBooking.DecididoPor quien) {
        if (!NUEVA_VIGENTE.contains(BookingStatus.valueOf(nueva.estado()))) {
            throw new ApiException(HttpStatus.CONFLICT, "RESCHEDULE_FAILED",
                    "No se pudo confirmar el viaje nuevo; elige otro", Map.of("bookingNuevo", nueva.bookingId()));
        }
        tx.executeWithoutResult(s -> affectedRepository.lockById(bookingId).ifPresent(a -> {
            if (a.pendiente()) {
                a.decidir(resultado, quien, nueva.bookingId(), clock.instant());
                saga.log(bookingId, "AFECTADA_" + resultado.name(), OK,
                        Map.of("bookingNuevoId", nueva.bookingId(), "decididoPor", quien.name()));
            }
        }));
    }

    /** ACEPTO_CAMBIO por el pasajero o por el sistema (al vencer el plazo). */
    void aceptar(UUID bookingId, AffectedBooking.DecididoPor quien) {
        tx.executeWithoutResult(s -> {
            AffectedBooking afectada = affectedRepository.lockById(bookingId).orElseThrow();
            if (!afectada.pendiente()) {
                throw yaDecidida(afectada);
            }
            afectada.decidir(AffectedBooking.Estado.ACEPTO_CAMBIO, quien, null, clock.instant());
            saga.log(bookingId, "AFECTADA_ACEPTO_CAMBIO", OK, Map.of("decididoPor", quien.name()));
        });
    }

    /**
     * La reserva ya no está CONFIRMADA con su tiquete EMITIDO porque el pasajero resolvió por su cuenta: reprogramó
     * con US-150 (REPROGRAMADA, con la reserva nueva) o abordó (ACEPTO_CAMBIO). Queda como decisión del pasajero.
     */
    void cerrar(UUID bookingId, AffectedBooking.Estado resultado) {
        UUID nueva = resultado == AffectedBooking.Estado.REPROGRAMADA
                ? bookingRepository.findFirstByOrigenBookingIdOrderByCreadoEnDesc(bookingId).map(Booking::getId)
                .orElse(null)
                : null;
        tx.executeWithoutResult(s -> affectedRepository.lockById(bookingId).ifPresent(a -> {
            if (a.pendiente()) {
                a.decidir(resultado, AffectedBooking.DecididoPor.PASAJERO, nueva, clock.instant());
                saga.log(bookingId, "AFECTADA_" + resultado.name(), OK, Map.of("decididoPor", "PASAJERO"));
            }
        }));
    }

    private AffectedBooking paraDecidir(UUID bookingId, BookingViewer viewer) {
        AffectedBooking afectada = propia(bookingId, viewer);
        validarPendiente(afectada);
        return afectada;
    }

    private void validarPendiente(AffectedBooking afectada) {
        if (!afectada.pendiente()) {
            throw yaDecidida(afectada);
        }
        if (afectada.plazoVencido(clock.instant())) {
            throw ApiException.conflict("DECISION_WINDOW_CLOSED",
                    "Venció el plazo para elegir: el sistema decidirá en unos minutos");
        }
    }

    /** Solo el dueño decide; OPERADOR y ADMIN consultan (403), los demás reciben 404. */
    private AffectedBooking propia(UUID bookingId, BookingViewer viewer) {
        Booking booking = bookingRepository.findById(bookingId).filter(viewer::canSee)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "La reserva no existe"));
        if (!booking.getUserId().equals(viewer.userId())) {
            throw ApiException.forbidden("NOT_BOOKING_OWNER", "Solo el dueño de la reserva puede decidir");
        }
        return afectada(bookingId);
    }

    private AffectedBooking afectada(UUID bookingId) {
        return affectedRepository.findById(bookingId).orElseThrow(() -> ApiException.notFound("BOOKING_NOT_AFFECTED",
                "La reserva no está afectada por un cambio de la empresa"));
    }

    static ApiException yaDecidida(AffectedBooking afectada) {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_DECIDED", "La reserva afectada ya se decidió",
                Map.of("estado", afectada.getEstado().name()));
    }

    AffectedBookingResponse detalle(UUID bookingId) {
        return toResponse(affectedRepository.findById(bookingId).orElseThrow());
    }

    private UUID ticketId(UUID bookingId) {
        return ticketRepository.findByBookingId(bookingId).map(Ticket::getId).orElse(null);
    }

    private AffectedBookingResponse toResponse(AffectedBooking a) {
        return new AffectedBookingResponse(a.getBookingId(), ticketId(a.getBookingId()), a.getItineraryId(),
                a.getCausa().name(), a.getEstado().name(), a.getPlazoDecision(),
                a.opciones().stream().map(Enum::name).toList(), a.getDecididoEn(),
                a.getDecididoPor() != null ? a.getDecididoPor().name() : null, a.getBookingNuevoId());
    }
}
