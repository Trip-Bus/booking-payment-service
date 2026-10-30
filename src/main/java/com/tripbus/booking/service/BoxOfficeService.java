package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.IdentityClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.PaymentStatus;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.events.BookingEvents;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.BoxOfficeSaleRequest;
import com.tripbus.booking.web.dto.BoxOfficeSaleResponse;
import com.tripbus.booking.web.dto.CashClosingResponse;
import com.tripbus.booking.web.dto.PassengerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * US-144: venta en taquilla con pago en efectivo. El OPERADOR vende asientos de un viaje de su empresa que no esté
 * cancelado ni haya salido: retiene los asientos en seat-inventory a su nombre, registra la reserva con un pago
 * EFECTIVO aprobado al instante (sin pasarela) y confirma la venta con el mismo paso de la saga de la app (asiento
 * vendido y tiquete emitido; si el comprador dio correo, se le envía). Cada paso queda en saga_log. La reserva queda
 * a nombre del pasajero registrado con el correo del comprador o, si no hay, del operador.
 */
@Service
public class BoxOfficeService {

    private static final Logger log = LoggerFactory.getLogger(BoxOfficeService.class);

    private static final String NO_EXISTE = "ITINERARY_NOT_FOUND";

    private final BookingRepository bookingRepository;
    private final PassengerRepository passengerRepository;
    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final CatalogClient catalog;
    private final SeatInventoryClient seatInventory;
    private final IdentityClient identity;
    private final PaymentService paymentService;
    private final SagaLogger saga;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    @SuppressWarnings("java:S107") // dependencias de la venta en taquilla, inyectadas por Spring
    public BoxOfficeService(BookingRepository bookingRepository, PassengerRepository passengerRepository,
                            PaymentRepository paymentRepository, TicketRepository ticketRepository,
                            CatalogClient catalog, SeatInventoryClient seatInventory, IdentityClient identity,
                            PaymentService paymentService, SagaLogger saga, EventPublisher events,
                            TransactionTemplate tx, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.passengerRepository = passengerRepository;
        this.paymentRepository = paymentRepository;
        this.ticketRepository = ticketRepository;
        this.catalog = catalog;
        this.seatInventory = seatInventory;
        this.identity = identity;
        this.paymentService = paymentService;
        this.saga = saga;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /** {@code idempotencyKey} ya la validó IdempotencyService; aquí identifica el pago en efectivo. */
    public BoxOfficeSaleResponse sell(BoxOfficeSaleRequest request, BookingViewer operador, String idempotencyKey) {
        List<String> asientos = request.seatNumbers().stream().map(String::trim).toList();
        if (new HashSet<>(asientos).size() != asientos.size()) {
            throw ApiException.badRequest("DUPLICATE_SEATS", "Hay asientos repetidos en la venta");
        }
        if (request.pasajeros().size() != asientos.size()) {
            throw ApiException.badRequest("PASSENGERS_MISMATCH",
                    "Debe registrar un pasajero por cada asiento (" + asientos.size() + ")");
        }
        CatalogClient.ItinerarySummary viaje = viajeVendible(request.itineraryId(), operador);
        CatalogClient.Price precio = catalog.price(request.itineraryId());
        if (!"PROGRAMADO".equals(precio.estado())) {
            throw ApiException.conflict("ITINERARY_NOT_AVAILABLE", "El viaje ya no está disponible");
        }
        String email = normalizar(request.comprador().email());
        UUID registrado = email == null ? null : identity.passengerIdByEmail(email).orElse(null);
        UUID duenio = registrado != null ? registrado : operador.userId();

        // Retención a nombre del operador; la venta se confirma de inmediato (no espera pago de la pasarela).
        SeatInventoryClient.Hold hold = seatInventory.createHold(request.itineraryId(), asientos, operador.userId());
        Venta venta = new Venta(request, hold, viaje, precio, duenio,
                new Booking.VentaTaquilla(operador.userId(), request.comprador().nombre().trim(), email,
                        normalizar(request.comprador().telefono())),
                "taquilla:" + operador.userId() + ":" + idempotencyKey);
        UUID bookingId;
        try {
            bookingId = Objects.requireNonNull(tx.execute(s -> registrar(venta)));
        } catch (RuntimeException e) {
            liberar(hold.holdId());
            throw e;
        }
        paymentService.confirmAndIssue(bookingId);
        return respuesta(bookingId, registrado != null);
    }

    /** Viaje de la empresa del operador (si no, 404 como si no existiera), no cancelado y que no ha salido. */
    private CatalogClient.ItinerarySummary viajeVendible(UUID itineraryId, BookingViewer operador) {
        if (operador.companyId() == null) {
            throw ApiException.forbidden("OPERATOR_WITHOUT_COMPANY", "El operador no tiene una empresa asignada");
        }
        CatalogClient.ItinerarySummary viaje = catalog.requireItinerary(itineraryId);
        if (!operador.companyId().equals(viaje.companyId())) {
            throw ApiException.notFound(NO_EXISTE, "El viaje no existe");
        }
        if ("CANCELADO".equals(viaje.estado())) {
            throw ApiException.conflict("ITINERARY_CANCELLED", "El viaje está cancelado");
        }
        if (viaje.fechaSalida() == null || !viaje.fechaSalida().isAfter(clock.instant())) {
            throw ApiException.conflict("ITINERARY_DEPARTED", "El viaje ya salió");
        }
        return viaje;
    }

    private record Venta(BoxOfficeSaleRequest request, SeatInventoryClient.Hold hold,
                         CatalogClient.ItinerarySummary viaje, CatalogClient.Price precio, UUID duenio,
                         Booking.VentaTaquilla datos, String claveDePago) {
    }

    /** Reserva, pasajeros y pago EFECTIVO aprobado, en una transacción. Devuelve el id de la reserva. */
    private UUID registrar(Venta v) {
        Instant now = clock.instant();
        SeatInventoryClient.Hold hold = v.hold();
        Booking nueva = Booking.taquilla(new Booking.SeatSelection(hold.holdId(), v.request().itineraryId(), null,
                        hold.seatNumbers(), hold.expiraEn()), v.duenio(), v.datos(), v.precio().precioBase(),
                v.precio().moneda(), v.viaje().companyId(), now);
        nueva.asignarViaje(BookingService.viajeDe(v.viaje()));
        // save() hace merge (el id ya viene asignado): se sigue con la instancia administrada que devuelve.
        Booking booking = bookingRepository.save(nueva);
        for (int i = 0; i < v.request().pasajeros().size(); i++) {
            PassengerRequest p = v.request().pasajeros().get(i);
            passengerRepository.save(new Passenger(booking.getId(), p.nombre().trim(), p.tipoDocumento(),
                    p.numeroDocumento().trim(), hold.seatNumbers().get(i)));
        }
        Map<String, Object> creada = new LinkedHashMap<>();
        creada.put("bookingId", booking.getId());
        creada.put("holdId", booking.getHoldId());
        creada.put("itineraryId", booking.getItineraryId());
        creada.put("userId", booking.getUserId());
        creada.put("total", booking.getTotal());
        creada.put("offerId", null);
        creada.put("canal", Booking.Canal.TAQUILLA.name());
        creada.put("vendidoPor", v.datos().vendidoPor());
        saga.log(booking.getId(), "RESERVA_CREADA", "OK", creada);
        events.publishAfterCommit(BookingEvents.BOOKING_CREATED, creada);

        booking.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, now);
        Payment pago = paymentRepository.save(Payment.efectivo(booking.getId(), booking.getTotal(), v.claveDePago(), now));
        booking.cambiarEstado(BookingStatus.PAGO_APROBADO, now);
        Map<String, Object> aprobado = new LinkedHashMap<>();
        aprobado.put("bookingId", booking.getId());
        aprobado.put("paymentId", pago.getId());
        aprobado.put("monto", pago.getMonto());
        aprobado.put("metodo", Payment.EFECTIVO);
        aprobado.put("timestamp", now);
        saga.log(booking.getId(), "PAGO_EFECTIVO_APROBADO", "OK", aprobado);
        events.publishAfterCommit(BookingEvents.PAYMENT_APPROVED, aprobado);
        return booking.getId();
    }

    private void liberar(UUID holdId) {
        try {
            seatInventory.releaseHold(holdId, "VENTA_TAQUILLA_FALLIDA");
        } catch (RuntimeException e) {
            log.warn("No se pudo liberar la retención {} de la venta en taquilla: {}", holdId, e.getMessage());
        }
    }

    private BoxOfficeSaleResponse respuesta(UUID bookingId, boolean registrado) {
        return tx.execute(s -> {
            Booking b = bookingRepository.findById(bookingId).orElseThrow();
            UUID ticketId = ticketRepository.findByBookingId(bookingId).map(Ticket::getId).orElse(null);
            var pasajeros = passengerRepository.findByBookingId(bookingId).stream()
                    .map(BookingService::toPassengerResponse).toList();
            return new BoxOfficeSaleResponse(b.getId(), b.getEstado().name(), ticketId, b.getUserId(), registrado,
                    b.getVendidoPor(), b.getItineraryId(), b.getOrigen(), b.getDestino(), b.getFechaSalida(),
                    b.getSeatNumbers(), pasajeros, b.getTotal(), b.getMoneda(), Payment.EFECTIVO, b.getCreadoEn());
        });
    }

    private static String normalizar(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.contains("@") ? valor.trim().toLowerCase(Locale.ROOT) : valor.trim();
    }

    // ------------------------------------------------------------------ cierre de caja

    /**
     * Ventas en taquilla del día {@code fecha} (hora de Colombia; hoy si es null). El OPERADOR ve las suyas o, con
     * {@code vendedor}, las de otro vendedor de su empresa; el ADMIN ve todas o las de {@code vendedor}.
     */
    @Transactional(readOnly = true)
    public CashClosingResponse cashClosing(BookingViewer viewer, LocalDate fecha, UUID vendedor) {
        Filtro filtro = Filtro.de(viewer, vendedor);
        LocalDate dia = fecha != null ? fecha : clock.instant().atZone(TicketMailService.ZONA).toLocalDate();
        Instant desde = dia.atStartOfDay(TicketMailService.ZONA).toInstant();
        Instant hasta = dia.plusDays(1).atStartOfDay(TicketMailService.ZONA).toInstant();
        List<Booking> ventas = bookingRepository.findVentasTaquilla(desde, hasta).stream().filter(filtro::incluye)
                .toList();
        Map<UUID, Ticket> tiquetes = ventas.isEmpty() ? Map.of()
                : ticketRepository.findByBookingIdIn(ventas.stream().map(Booking::getId).toList()).stream()
                .collect(Collectors.toMap(Ticket::getBookingId, Function.identity()));

        BigDecimal efectivo = BigDecimal.ZERO;
        BigDecimal reembolsado = BigDecimal.ZERO;
        int asientos = 0;
        List<CashClosingResponse.Venta> detalle = new ArrayList<>();
        for (Booking b : ventas) {
            Payment pago = paymentRepository.findByBookingIdOrderByCreadoEnDesc(b.getId()).stream()
                    .filter(Payment::enEfectivo).findFirst().orElse(null);
            if (pago != null && pago.getEstado() == PaymentStatus.APROBADO) {
                efectivo = efectivo.add(pago.getMonto());
            } else if (pago != null && pago.getEstado() == PaymentStatus.REEMBOLSADO) {
                reembolsado = reembolsado.add(pago.getMonto());
            }
            asientos += b.getSeatNumbers().size();
            detalle.add(venta(b, tiquetes.get(b.getId()), pago));
        }
        return new CashClosingResponse(dia, filtro.vendedor(), ventas.size(), tiquetes.size(), asientos, efectivo,
                reembolsado, detalle);
    }

    /** Qué ventas ve quien pide el cierre: vendedor y empresa null = todos. */
    record Filtro(UUID vendedor, UUID empresa) {

        static Filtro de(BookingViewer viewer, UUID vendedor) {
            if (viewer.admin()) {
                return new Filtro(vendedor, null);
            }
            if (!viewer.operador() || viewer.companyId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "El cierre de caja es del operador de la empresa o del ADMIN");
            }
            return new Filtro(vendedor != null ? vendedor : viewer.userId(), viewer.companyId());
        }

        boolean incluye(Booking b) {
            return (vendedor == null || vendedor.equals(b.getVendidoPor()))
                    && (empresa == null || empresa.equals(b.getCompanyId()));
        }
    }

    private static CashClosingResponse.Venta venta(Booking b, Ticket t, Payment pago) {
        return new CashClosingResponse.Venta(b.getId(), b.getEstado().name(), t != null ? t.getId() : null,
                t != null ? t.getEstado() : null, b.getVendidoPor(), b.getCreadoEn(), b.getItineraryId(),
                b.getOrigen(), b.getDestino(), b.getFechaSalida(), b.getSeatNumbers(), b.getCompradorNombre(),
                b.getTotal(), b.getMoneda(), pago != null ? pago.getEstado().name() : null);
    }
}
