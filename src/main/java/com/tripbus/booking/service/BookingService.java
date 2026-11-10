package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
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
import com.tripbus.booking.web.dto.TramoResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final SagaLogger saga;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final TarifaService tarifas;
    private final CouponService coupons;

    @SuppressWarnings("java:S107") // dependencias de la compra, inyectadas por Spring
    public BookingService(BookingRepository bookingRepository, PassengerRepository passengerRepository,
                          PaymentRepository paymentRepository, TicketRepository ticketRepository,
                          SagaLogRepository sagaLogRepository, SeatInventoryClient seatInventory,
                          CatalogClient catalog, SagaLogger saga,
                          EventPublisher events, TransactionTemplate tx, Clock clock, TarifaService tarifas,
                          CouponService coupons) {
        this.tarifas = tarifas;
        this.coupons = coupons;
        this.bookingRepository = bookingRepository;
        this.passengerRepository = passengerRepository;
        this.paymentRepository = paymentRepository;
        this.ticketRepository = ticketRepository;
        this.sagaLogRepository = sagaLogRepository;
        this.seatInventory = seatInventory;
        this.catalog = catalog;
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
     * {@code emailContacto} (claim "email" del JWT, puede ser null) es a donde se enviará el tiquete.
     */
    public CreateResult create(CreateBookingRequest request, UUID userId, String emailContacto) {
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

        // US-138/US-141: el precio lo calcula catalog (tramo, temporada, tipo de pasajero y categoría del asiento).
        List<String> tipos = request.pasajeros().stream().map(p -> TarifaService.tipo(p.tipoPasajero())).toList();
        TarifaService.Tarifa tarifa = tarifas.calcular(hold, tipos, userId);
        // US-005: empresa del itinerario para la visibilidad del OPERADOR. Si catalog no responde la
        // reserva se crea igual, sin empresa (solo la verán su dueño y ADMIN).
        CatalogClient.ItinerarySummary itinerario = catalog.itinerary(hold.itineraryId());
        UUID companyId = itinerario != null ? itinerario.companyId() : null;
        UUID routeId = itinerario != null ? itinerario.routeId() : null;
        // US-147: el cupón se valida antes de crear la reserva y su uso se gasta en la misma transacción.
        CouponService.Descuento descuento = request.cupon() == null || request.cupon().isBlank() ? null
                : coupons.validar(request.cupon(), new CouponService.Uso(companyId, routeId, tarifa.subtotal(),
                tarifa.deOferta(), userId));

        try {
            BookingResponse response = tx.execute(status -> {
                Instant now = clock.instant();
                Booking nueva = new Booking(hold.holdId(), userId, hold.itineraryId(), hold.offerId(),
                        hold.seatNumbers(), tarifa.subtotal(), tarifa.moneda(), hold.expiraEn(), emailContacto,
                        companyId, now);
                nueva.asignarTarifa(tarifa.subtotal(), descuento != null ? descuento.valor() : null,
                        descuento != null ? descuento.couponId() : null, descuento != null ? descuento.codigo() : null);
                nueva.asignarTramo(tarifa.tramo());
                nueva.asignarRuta(routeId);
                nueva.asignarViaje(viajeDe(itinerario));
                Booking booking = bookingRepository.save(nueva);
                for (int i = 0; i < request.pasajeros().size(); i++) {
                    PassengerRequest p = request.pasajeros().get(i);
                    Passenger pasajero = new Passenger(booking.getId(), p.nombre().trim(), p.tipoDocumento(),
                            p.numeroDocumento().trim(), hold.seatNumbers().get(i));
                    tarifa.pasajeros().get(i).aplicarA(pasajero);
                    passengerRepository.save(pasajero);
                }
                if (descuento != null) {
                    coupons.redimir(descuento, booking.getId(), userId);
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
    public BookingResponse get(UUID bookingId, BookingViewer viewer) {
        // Si no la puede ver responde 404 igual que si no existiera: no se revela que existe.
        Booking booking = bookingRepository.findById(bookingId)
                .filter(viewer::canSee)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "La reserva no existe"));
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> mine(UUID userId) {
        return bookingRepository.findByUserIdOrderByCreadoEnDesc(userId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<SagaStepResponse> sagaLog(UUID bookingId, BookingViewer viewer) {
        get(bookingId, viewer);
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
                coupons.liberar(booking.getId());
                saga.log(booking.getId(), "RETENCION_EXPIRADA", "OK", Map.of("holdId", holdId));
            }
        });
    }

    /**
     * ItineraryRescheduled: la nueva salida queda en todas las reservas del viaje (mis viajes, no-show y
     * reprogramaciones se calculan con ella). Devuelve cuántas reservas se actualizaron.
     */
    @Transactional
    public int onItineraryRescheduled(UUID itineraryId, Instant nuevaSalida) {
        return nuevaSalida == null ? 0 : bookingRepository.actualizarSalida(itineraryId, nuevaSalida);
    }

    /** US-145: datos del viaje que se guardan en la reserva (null si catalog no respondió). */
    static Booking.Viaje viajeDe(CatalogClient.ItinerarySummary itinerario) {
        if (itinerario == null) {
            return null;
        }
        return new Booking.Viaje(itinerario.origen(), itinerario.destino(), itinerario.fechaSalida(),
                itinerario.empresa());
    }

    private BookingResponse existingFor(Booking booking, UUID userId) {
        if (!booking.getUserId().equals(userId)) {
            throw ApiException.conflict("HOLD_ALREADY_USED", "La retención ya fue usada en otra reserva");
        }
        return tx.execute(s -> toResponse(booking));
    }

    BookingResponse toResponse(Booking booking) {
        List<PassengerResponse> pasajeros = passengerRepository.findByBookingId(booking.getId()).stream()
                .map(BookingService::toPassengerResponse)
                .toList();
        List<Payment> pagos = paymentRepository.findByBookingIdOrderByCreadoEnDesc(booking.getId());
        PaymentSummary pago = pagos.isEmpty() ? null : toSummary(pagos.get(0));
        UUID ticketId = ticketRepository.findByBookingId(booking.getId()).map(t -> t.getId()).orElse(null);
        return new BookingResponse(booking.getId(), booking.getEstado().name(), booking.getHoldId(),
                booking.getItineraryId(), booking.getOfferId(), booking.getSeatNumbers(), pasajeros,
                booking.getPrecioUnitario(), booking.getTotal(), booking.getMoneda(), booking.getHoldExpiraEn(),
                pago, ticketId, booking.getOrigenBookingId(), booking.getCreadoEn(), booking.getActualizadoEn(),
                booking.getSubtotal() != null ? booking.getSubtotal() : booking.getTotal(), booking.getDescuentoCupon(),
                booking.getCuponCodigo(), tramo(booking.getTramo()));
    }

    static TramoResponse tramo(Booking.Tramo tramo) {
        return tramo == null ? null
                : new TramoResponse(tramo.fromOrden(), tramo.paradaAbordaje(), tramo.toOrden(), tramo.paradaBajada());
    }

    static PassengerResponse toPassengerResponse(Passenger p) {
        return new PassengerResponse(p.getId(), p.getNombre(), p.getTipoDocumento(), p.getNumeroDocumento(),
                p.getAsiento(), p.getTipoPasajero(), p.getCategoriaAsiento(), p.getPrecio());
    }

    static PaymentSummary toSummary(Payment p) {
        return new PaymentSummary(p.getId(), p.getEstado().name(), p.getMonto(), p.getMetodo(), p.getTarjetaUltimos4(),
                p.getMotivoRechazo(), p.getActualizadoEn());
    }
}
