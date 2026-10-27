package com.tripbus.booking.service;

import com.tripbus.booking.client.RemoteErrors;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.domain.AffectedBooking;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.PaymentStatus;
import com.tripbus.booking.domain.Refund;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.events.BookingEvents;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.repository.AffectedBookingRepository;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.RefundRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * US-154/US-155: reembolso de una reserva afectada por la empresa (la ÚNICA vía de reembolso: el pasajero no tuvo la
 * culpa). Se devuelve todo lo pagado en la cadena de reprogramaciones, una sola vez (refunds es único por pago). El
 * tiquete pasa a ANULADO y la reserva a REEMBOLSADA en la misma transacción. Si el viaje no fue cancelado, después se
 * anulan los asientos en seat-inventory (motivo REEMBOLSO); si no responde, el job de afectadas lo reintenta.
 */
@Service
public class AffectedRefundService {

    private static final Logger log = LoggerFactory.getLogger(AffectedRefundService.class);

    static final String MOTIVO = "REEMBOLSO";
    private static final String OK = "OK";
    static final String PASO_ANULACION = "ANULAR_ASIENTOS_REEMBOLSO";
    /** Tope de la cadena de reprogramaciones que se recorre buscando pagos (US-150 permite 2). */
    private static final int MAX_CADENA = 10;

    private final AffectedBookingRepository affectedRepository;
    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway gateway;
    private final SeatInventoryClient seatInventory;
    private final SagaLogger saga;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    @SuppressWarnings("java:S107") // dependencias del reembolso, inyectadas por Spring
    public AffectedRefundService(AffectedBookingRepository affectedRepository, BookingRepository bookingRepository,
                                 TicketRepository ticketRepository, PaymentRepository paymentRepository,
                                 RefundRepository refundRepository, PaymentGateway gateway,
                                 SeatInventoryClient seatInventory, SagaLogger saga, EventPublisher events,
                                 TransactionTemplate tx, Clock clock) {
        this.affectedRepository = affectedRepository;
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.gateway = gateway;
        this.seatInventory = seatInventory;
        this.saga = saga;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Reembolsa la reserva afectada y la deja decidida por {@code quien}. Idempotente: si ya estaba REEMBOLSADA no
     * hace nada. 409 ALREADY_DECIDED si se decidió otra cosa; 409 BOOKING_NOT_REFUNDABLE si la reserva ya no está
     * confirmada con su tiquete EMITIDO (p. ej. el pasajero abordó).
     */
    public void reembolsar(UUID bookingId, AffectedBooking.DecididoPor quien) {
        Boolean anularAsientos = tx.execute(s -> {
            AffectedBooking afectada = affectedRepository.lockById(bookingId).orElseThrow();
            if (afectada.getEstado() == AffectedBooking.Estado.REEMBOLSADA) {
                return Boolean.FALSE;
            }
            if (!afectada.pendiente()) {
                throw AffectedBookingService.yaDecidida(afectada);
            }
            Booking booking = bookingRepository.lockById(bookingId).orElseThrow();
            Ticket ticket = ticketRepository.findByBookingId(bookingId).orElse(null);
            if (booking.getEstado() != BookingStatus.CONFIRMADA || ticket == null
                    || ticketRepository.anular(ticket.getId()) != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "BOOKING_NOT_REFUNDABLE",
                        "La reserva ya no se puede reembolsar (el tiquete se usó o cambió)",
                        Map.of("estado", booking.getEstado().name()));
            }
            Instant now = clock.instant();
            for (Payment payment : pagosAprobados(booking)) {
                reembolsarPago(booking, payment, now);
            }
            booking.cambiarEstado(BookingStatus.REEMBOLSADA, now);
            afectada.decidir(AffectedBooking.Estado.REEMBOLSADA, quien, null, now);
            boolean cancelado = afectada.getCausa() == AffectedBooking.Causa.CANCELACION;
            if (cancelado) {
                // El viaje no sale: no hay asientos que devolver a la venta.
                afectada.marcarAnulacionResuelta(now);
            }
            saga.log(bookingId, "AFECTADA_REEMBOLSADA", OK,
                    Map.of("ticketId", ticket.getId(), "causa", afectada.getCausa().name(), "decididoPor", quien.name()));
            return !cancelado;
        });
        if (Boolean.TRUE.equals(anularAsientos)) {
            anularAsientos(bookingId);
        }
    }

    /** Pagos APROBADO de la reserva y de las que reprogramó (US-150): todo lo que el pasajero pagó por este viaje. */
    private List<Payment> pagosAprobados(Booking booking) {
        List<Payment> pagos = new ArrayList<>();
        UUID actual = booking.getId();
        for (int i = 0; actual != null && i < MAX_CADENA; i++) {
            paymentRepository.findByBookingIdOrderByCreadoEnDesc(actual).stream()
                    .filter(p -> p.getEstado() == PaymentStatus.APROBADO)
                    .forEach(pagos::add);
            actual = bookingRepository.findById(actual).map(Booking::getOrigenBookingId).orElse(null);
        }
        return pagos;
    }

    private void reembolsarPago(Booking booking, Payment payment, Instant now) {
        if (refundRepository.findByPaymentId(payment.getId()).isPresent()) {
            return;
        }
        gateway.refund(payment.getPaymentGatewayId(), payment.getMonto());
        Refund refund = refundRepository.save(new Refund(payment.getId(), payment.getMonto(), "AFECTADA_POR_LA_EMPRESA",
                now));
        payment.reembolsar(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("paymentId", payment.getId());
        payload.put("refundId", refund.getId());
        payload.put("monto", refund.getMonto());
        payload.put("userId", booking.getUserId());
        payload.put("motivo", "AFECTADA_POR_LA_EMPRESA");
        saga.log(booking.getId(), "REEMBOLSO", OK, payload);
        events.publishAfterCommit(BookingEvents.REFUND_PROCESSED, payload);
    }

    /** Devuelve a la venta los asientos de una reserva reembolsada de un viaje que sí sale. Idempotente. */
    public void anularAsientos(UUID bookingId) {
        AffectedBooking afectada = affectedRepository.findById(bookingId).orElse(null);
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (afectada == null || booking == null || afectada.getAnulacionResueltaEn() != null) {
            return;
        }
        String estado;
        try {
            seatInventory.voidSale(booking.getItineraryId(), bookingId, booking.getSeatNumbers(), MOTIVO);
            estado = OK;
        } catch (ApiException e) {
            // Seat-inventory la rechazó: reintentar no lo arregla. Queda para soporte.
            log.error("Seat Inventory rechazó anular los asientos de la reserva {} ({})", bookingId, e.getCode());
            estado = "ERROR";
        } catch (RemoteErrors.RemoteUnavailableException e) {
            log.warn("Seat Inventory no disponible anulando los asientos de la reserva {}; se reintentará", bookingId);
            tx.executeWithoutResult(s -> saga.log(bookingId, PASO_ANULACION, "REINTENTO",
                    Map.of("error", String.valueOf(e.getMessage()))));
            return;
        }
        String resultado = estado;
        tx.executeWithoutResult(s -> affectedRepository.lockById(bookingId).ifPresent(a -> {
            if (a.getAnulacionResueltaEn() == null) {
                a.marcarAnulacionResuelta(clock.instant());
                saga.log(bookingId, PASO_ANULACION, resultado, Map.of("seatNumbers", booking.getSeatNumbers()));
            }
        }));
    }
}
