package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.events.BookingEvents;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.SagaLogRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.BookingResponse;
import com.tripbus.booking.web.dto.CreateBookingRequest;
import com.tripbus.booking.web.dto.PassengerRequest;
import com.tripbus.booking.web.dto.PassengerResponse;
import com.tripbus.booking.web.dto.PaymentSummary;
import com.tripbus.booking.web.dto.SagaStepResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final PassengerRepository passengerRepository;
    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final SagaLogRepository sagaLogRepository;
    private final SeatInventoryClient seatInventory;
    private final CatalogClient catalog;
    private final FlashOffersClient flashOffers;
    private final SagaLogger saga;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository, PassengerRepository passengerRepository,
                          PaymentRepository paymentRepository, TicketRepository ticketRepository,
                          SagaLogRepository sagaLogRepository, SeatInventoryClient seatInventory,
                          CatalogClient catalog, FlashOffersClient flashOffers, SagaLogger saga,
                          EventPublisher events, TransactionTemplate tx, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.passengerRepository = passengerRepository;
        this.paymentRepository = paymentRepository;
        this.ticketRepository = ticketRepository;
        this.sagaLogRepository = sagaLogRepository;
        this.seatInventory = seatInventory;
        this.catalog = catalog;
        this.flashOffers = flashOffers;
        this.saga = saga;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public record CreateResult(BookingResponse booking, boolean created) {
    }

    /**
     * US-031 (pasos 1-3 de la saga): valida la retención con Seat Inventory, calcula el
     * total (Catalog o Flash Offers) y registra la reserva con los datos del viajero.
     */
    public CreateResult create(CreateBookingRequest request, UUID userId) {
        var existing = bookingRepository.findByHoldId(request.holdId());
        if (existing.isPresent()) {
            return new CreateResult(existingFor(existing.get(), userId), false);
        }

        SeatInventoryClient.Hold hold = seatInventory.getHold(request.holdId());
        if (!hold.userId().equals(userId)) {
            throw ApiException.notFound("HOLD_NOT_FOUND", "La retención no existe");
        }
        if (!"ACTIVA".equals(hold.estado()) || hold.segundosRestantes() <= 0) {
            throw ApiException.conflict("HOLD_NOT_ACTIVE", "La retención del asiento ya no está activa");
        }
        if (request.pasajeros().size() != hold.seatNumbers().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSENGERS_MISMATCH",
                    "Debe registrar un viajero por cada asiento retenido (" + hold.seatNumbers().size() + ")");
        }

        BigDecimal precioUnitario;
        String moneda = "COP";
        if (hold.offerId() != null) {
            FlashOffersClient.OfferReservation reservation = flashOffers.reservationByHold(hold.offerId(), hold.holdId());
            if (!userId.equals(reservation.userId())) {
                throw ApiException.forbidden("OFFER_RESERVATION_MISMATCH", "La reserva de oferta no pertenece al usuario");
            }
            precioUnitario = reservation.precioOferta();
        } else {
            CatalogClient.Price price = catalog.price(hold.itineraryId());
            if (!"PROGRAMADO".equals(price.estado())) {
                throw ApiException.conflict("ITINERARY_NOT_AVAILABLE", "El viaje ya no está disponible");
            }
            precioUnitario = price.precioBase();
            moneda = price.moneda();
        }

        final String monedaFinal = moneda;
        try {
            BookingResponse response = tx.execute(status -> {
                Instant now = clock.instant();
                Booking booking = bookingRepository.save(new Booking(hold.holdId(), userId, hold.itineraryId(),
                        hold.offerId(), hold.seatNumbers(), precioUnitario, monedaFinal, hold.expiraEn(), now));
                for (int i = 0; i < request.pasajeros().size(); i++) {
                    PassengerRequest p = request.pasajeros().get(i);
                    passengerRepository.save(new Passenger(booking.getId(), p.nombre().trim(), p.tipoDocumento(),
                            p.numeroDocumento().trim(), hold.seatNumbers().get(i)));
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("bookingId", booking.getId());
                payload.put("holdId", booking.getHoldId());
                payload.put("itineraryId", booking.getItineraryId());
                payload.put("userId", userId);
                payload.put("total", booking.getTotal());
                payload.put("offerId", booking.getOfferId());
                saga.log(booking.getId(), "RESERVA_CREADA", "OK", payload);
                events.publishAfterCommit(BookingEvents.BOOKING_CREATED, payload);
                return toResponse(booking);
            });
            return new CreateResult(response, true);
        } catch (DataIntegrityViolationException e) {
            // Dos solicitudes simultáneas con la misma retención: gana una, la otra recibe la misma reserva.
            Booking booking = bookingRepository.findByHoldId(request.holdId()).orElseThrow(() -> e);
            return new CreateResult(existingFor(booking, userId), false);
        }
    }

    @Transactional(readOnly = true)
    public BookingResponse get(UUID bookingId, UUID requesterId, boolean staff) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "La reserva no existe"));
        if (!staff && !booking.getUserId().equals(requesterId)) {
            throw ApiException.notFound("BOOKING_NOT_FOUND", "La reserva no existe");
        }
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> mine(UUID userId) {
        return bookingRepository.findByUserIdOrderByCreadoEnDesc(userId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<SagaStepResponse> sagaLog(UUID bookingId, UUID requesterId, boolean staff) {
        get(bookingId, requesterId, staff);
        return sagaLogRepository.findByBookingIdOrderByIdAsc(bookingId).stream()
                .map(s -> new SagaStepResponse(s.getPaso(), s.getEstado(), s.getPayload(), s.getCreadoEn()))
                .toList();
    }

    /** HoldExpired: una reserva que nunca se pagó queda expirada. */
    @Transactional
    public void onHoldExpired(UUID holdId) {
        bookingRepository.findByHoldId(holdId).ifPresent(b -> {
            Booking booking = bookingRepository.lockById(b.getId()).orElseThrow();
            if (booking.getEstado() == BookingStatus.PENDIENTE_PAGO) {
                booking.cambiarEstado(BookingStatus.EXPIRADA, clock.instant());
                saga.log(booking.getId(), "RETENCION_EXPIRADA", "OK", Map.of("holdId", holdId));
            }
        });
    }

    private BookingResponse existingFor(Booking booking, UUID userId) {
        if (!booking.getUserId().equals(userId)) {
            throw ApiException.conflict("HOLD_ALREADY_USED", "La retención ya fue usada en otra reserva");
        }
        return tx.execute(s -> toResponse(booking));
    }

    BookingResponse toResponse(Booking booking) {
        List<PassengerResponse> pasajeros = passengerRepository.findByBookingId(booking.getId()).stream()
                .map(p -> new PassengerResponse(p.getNombre(), p.getTipoDocumento(), p.getNumeroDocumento(),
                        p.getAsiento()))
                .toList();
        List<Payment> pagos = paymentRepository.findByBookingIdOrderByCreadoEnDesc(booking.getId());
        PaymentSummary pago = pagos.isEmpty() ? null : toSummary(pagos.get(0));
        UUID ticketId = ticketRepository.findByBookingId(booking.getId()).map(t -> t.getId()).orElse(null);
        return new BookingResponse(booking.getId(), booking.getEstado().name(), booking.getHoldId(),
                booking.getItineraryId(), booking.getOfferId(), booking.getSeatNumbers(), pasajeros,
                booking.getPrecioUnitario(), booking.getTotal(), booking.getMoneda(), booking.getHoldExpiraEn(),
                pago, ticketId, booking.getCreadoEn(), booking.getActualizadoEn());
    }

    static PaymentSummary toSummary(Payment p) {
        return new PaymentSummary(p.getId(), p.getEstado().name(), p.getMonto(), p.getMetodo(), p.getTarjetaUltimos4(),
                p.getMotivoRechazo(), p.getActualizadoEn());
    }
}
