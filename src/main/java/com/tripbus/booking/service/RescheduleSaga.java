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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * US-150: paso de la saga que aplica una reprogramación cuando se confirma la reserva nueva
 * ({@link PaymentService#confirmAndIssue}). En la MISMA transacción que emite el tiquete nuevo, el tiquete viejo
 * pasa a REPROGRAMADO (UPDATE condicional que compite con el abordaje) y la reserva vieja a REPROGRAMADA: nunca
 * quedan dos tiquetes válidos. Después se anulan en seat-inventory los asientos viejos; si no responde,
 * {@link SagaRecoveryJob} lo reintenta.
 */
@Component
public class RescheduleSaga {

    private static final Logger log = LoggerFactory.getLogger(RescheduleSaga.class);

    static final String MOTIVO_ANULACION = "REPROGRAMACION";
    static final String PASO_ANULACION = "ANULAR_ASIENTOS_ANTERIORES";
    private static final String OK = "OK";

    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final SeatInventoryClient seatInventory;
    private final SagaLogger saga;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RescheduleSaga(BookingRepository bookingRepository, TicketRepository ticketRepository,
                          SeatInventoryClient seatInventory, SagaLogger saga, EventPublisher events,
                          TransactionTemplate tx, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.seatInventory = seatInventory;
        this.saga = saga;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /** Tiquete y reserva que la reprogramación reemplazó. */
    public record Retiro(Booking origen, UUID ticketAnteriorId) {
    }

    /** La reserva original sigue CONFIRMADA y su tiquete EMITIDO: la reprogramación todavía se puede aplicar. */
    public boolean sigueVigente(Booking nueva) {
        Booking origen = bookingRepository.findById(nueva.getOrigenBookingId()).orElse(null);
        return origen != null && origen.getEstado() == BookingStatus.CONFIRMADA
                && ticketRepository.findByBookingId(origen.getId())
                .map(t -> TicketService.EMITIDO.equals(t.getEstado())).orElse(false);
    }

    /**
     * Dentro de la transacción que confirma la reserva nueva: tiquete viejo a REPROGRAMADO y reserva vieja a
     * REPROGRAMADA. Devuelve null (sin cambiar nada) si ya no se puede porque el tiquete viejo se usó para abordar
     * o la reserva vieja cambió de estado.
     */
    public Retiro retirarAnterior(Booking nueva) {
        Booking origen = bookingRepository.lockById(nueva.getOrigenBookingId()).orElseThrow();
        Ticket anterior = ticketRepository.findByBookingId(origen.getId()).orElse(null);
        if (origen.getEstado() != BookingStatus.CONFIRMADA || anterior == null
                || ticketRepository.marcarReprogramado(anterior.getId()) != 1) {
            return null;
        }
        origen.cambiarEstado(BookingStatus.REPROGRAMADA, clock.instant());
        return new Retiro(origen, anterior.getId());
    }

    /** Bitácora de las dos reservas y TicketRescheduled (tras el commit). Requiere la transacción de confirmación. */
    public void registrar(Booking nueva, Retiro retiro, Ticket nuevo) {
        Booking origen = retiro.origen();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", nueva.getUserId());
        payload.put("bookingIdAnterior", origen.getId());
        payload.put("bookingIdNuevo", nueva.getId());
        payload.put("ticketIdAnterior", retiro.ticketAnteriorId());
        payload.put("ticketIdNuevo", nuevo.getId());
        payload.put("itineraryIdAnterior", origen.getItineraryId());
        payload.put("itineraryIdNuevo", nueva.getItineraryId());
        saga.log(nueva.getId(), "REPROGRAMACION_APLICADA", OK, payload);
        saga.log(origen.getId(), "REPROGRAMADA", OK, payload);
        events.publishAfterCommit(BookingEvents.TICKET_RESCHEDULED, payload);
    }

    /**
     * Anula en seat-inventory (motivo REPROGRAMACION) los asientos de la reserva REPROGRAMADA. Idempotente: si ya
     * quedó resuelta no hace nada. Si seat-inventory no responde queda REINTENTO en saga_log y el job de
     * recuperación la vuelve a intentar.
     */
    public void anularAsientosAnteriores(UUID origenId) {
        Booking origen = bookingRepository.findById(origenId).orElse(null);
        if (origen == null || origen.getEstado() != BookingStatus.REPROGRAMADA
                || origen.getAnulacionResueltaEn() != null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", origen.getItineraryId());
        payload.put("seatNumbers", origen.getSeatNumbers());
        payload.put("motivo", MOTIVO_ANULACION);
        String estado;
        try {
            seatInventory.voidSale(origen.getItineraryId(), origen.getId(), origen.getSeatNumbers(), MOTIVO_ANULACION);
            estado = OK;
        } catch (ApiException e) {
            // Seat-inventory la rechazó (p. ej. SEAT_NOT_OWNED): reintentar no lo arregla. Queda para soporte.
            log.error("Seat Inventory rechazó anular los asientos de la reserva {} ({}): {}", origenId, e.getCode(),
                    e.getMessage());
            payload.put("error", e.getCode());
            estado = "ERROR";
        } catch (RemoteErrors.RemoteUnavailableException e) {
            log.warn("Seat Inventory no disponible anulando los asientos de la reserva {}; se reintentará", origenId);
            tx.executeWithoutResult(s -> saga.log(origenId, PASO_ANULACION, "REINTENTO",
                    Map.of("error", String.valueOf(e.getMessage()))));
            return;
        }
        String resultado = estado;
        tx.executeWithoutResult(s -> bookingRepository.lockById(origenId).ifPresent(b -> {
            if (b.getAnulacionResueltaEn() == null) {
                b.marcarAnulacionResuelta(clock.instant());
                saga.log(origenId, PASO_ANULACION, resultado, payload);
            }
        }));
    }

    /**
     * Compensación: el tiquete viejo se usó para abordar antes de aplicar la reprogramación, así que se devuelven
     * los asientos nuevos que ya se habían vendido (la reserva nueva se reembolsa aparte).
     */
    public void devolverAsientosNuevos(Booking nueva) {
        try {
            seatInventory.voidSale(nueva.getItineraryId(), nueva.getId(), nueva.getSeatNumbers(), "REEMBOLSO");
        } catch (RuntimeException e) {
            log.warn("No se pudieron devolver los asientos de la reserva {}: {}", nueva.getId(), e.getMessage());
        }
    }
}
