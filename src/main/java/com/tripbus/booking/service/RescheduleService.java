package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.events.BookingEvents;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * US-150: reprogramar el tiquete a otro viaje de la misma ruta y empresa. El pasajero retiene los asientos nuevos
 * con POST /seats/holds y llama a POST /bookings/{id}/reschedule {holdId}: se crea una reserva nueva con
 * origen_booking_id y los mismos pasajeros. Diferencia = max(0, pasaje nuevo − pasaje ya pagado) + cargo; no hay
 * reembolso si el viaje nuevo es más barato. Con diferencia se paga con el POST /bookings/{id}/pay de siempre; sin
 * diferencia se confirma de inmediato. La reserva original no se toca hasta que la nueva se confirma
 * ({@link RescheduleSaga}): si la nueva expira o su pago se rechaza, la original sigue intacta.
 */
@Service
public class RescheduleService {

    /** Estados en los que una reprogramación cuenta como viva (la BD tampoco permite dos a la vez). */
    static final Set<BookingStatus> VIVAS = EnumSet.of(BookingStatus.PENDIENTE_PAGO, BookingStatus.PAGO_EN_PROCESO,
            BookingStatus.PAGO_APROBADO, BookingStatus.CONFIRMADA, BookingStatus.REPROGRAMADA);

    private static final String CATALOG_UNAVAILABLE = "CATALOG_UNAVAILABLE";
    private static final String EN_CURSO = "RESCHEDULE_IN_PROGRESS";
    private static final String EN_CURSO_MSG = "La reserva ya tiene una reprogramación en curso";
    private static final String ESTADO = "estado";

    private final BookingRepository bookingRepository;
    private final PassengerRepository passengerRepository;
    private final TicketRepository ticketRepository;
    private final SeatInventoryClient seatInventory;
    private final CatalogClient catalog;
    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final SagaLogger saga;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final BookingProperties properties;
    private final Clock clock;

    @SuppressWarnings("java:S107") // dependencias del flujo de reprogramación, inyectadas por Spring
    public RescheduleService(BookingRepository bookingRepository, PassengerRepository passengerRepository,
                             TicketRepository ticketRepository, SeatInventoryClient seatInventory,
                             CatalogClient catalog, BookingService bookingService, PaymentService paymentService,
                             SagaLogger saga, EventPublisher events, TransactionTemplate tx,
                             BookingProperties properties, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.passengerRepository = passengerRepository;
        this.ticketRepository = ticketRepository;
        this.seatInventory = seatInventory;
        this.catalog = catalog;
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.saga = saga;
        this.events = events;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Crea la reserva nueva (created = true) o, si esa retención ya se usó para reprogramar esta misma reserva,
     * devuelve la que ya existe (created = false). {@code emailContacto} es a donde se enviará el tiquete nuevo
     * (si el token no lo trae, el de la reserva original).
     */
    public BookingService.CreateResult reschedule(UUID bookingId, UUID holdId, BookingViewer viewer,
                                                  String emailContacto) {
        return reprogramar(propia(bookingId, viewer), holdId, viewer, emailContacto, Modo.VOLUNTARIA);
    }

    /**
     * US-154/US-155: reprogramación gratis de una reserva afectada por la empresa. Mismo flujo, pero sin
     * diferencia ni cargo, sin el límite de cambios, sin el plazo de 3 h y sin exigir la misma empresa (sí el
     * mismo origen y destino). Quien llama ya validó que la reserva está afectada y es de {@code viewer}.
     */
    public BookingService.CreateResult rescheduleAffected(Booking origen, UUID holdId, BookingViewer viewer) {
        return reprogramar(origen, holdId, viewer, null, Modo.AFECTADA);
    }

    /** VOLUNTARIA: decisión del pasajero (US-150). AFECTADA: la empresa cambió el viaje (US-154). */
    enum Modo { VOLUNTARIA, AFECTADA }

    private BookingService.CreateResult reprogramar(Booking origen, UUID holdId, BookingViewer viewer,
                                                    String emailContacto, Modo modo) {
        Booking existente = bookingRepository.findByHoldId(holdId).orElse(null);
        if (existente != null) {
            return yaCreada(origen, existente, viewer, "HOLD_ALREADY_USED", "La retención ya fue usada en otra reserva");
        }

        CatalogClient.ItinerarySummary viajeOriginal = validarOrigen(origen, modo);
        List<Passenger> pasajeros = pasajerosEnOrden(origen);
        SeatInventoryClient.Hold hold = validarRetencion(origen, holdId, pasajeros.size());
        validarViajeNuevo(viajeOriginal, origen, hold, modo);

        BookingService.Precio precio = bookingService.precio(hold, origen.getUserId());
        if (!origen.getMoneda().equalsIgnoreCase(precio.moneda())) {
            throw ApiException.conflict("CURRENCY_MISMATCH", "El viaje nuevo se vende en otra moneda");
        }
        BigDecimal pasajeNuevo = precio.unitario().multiply(BigDecimal.valueOf(hold.seatNumbers().size()));
        BigDecimal diferencia = modo == Modo.AFECTADA ? BigDecimal.ZERO
                : diferencia(pasajeNuevo, origen.getValorPagado(), properties.rescheduleFee());
        BigDecimal valorPagado = pasajeNuevo.max(origen.getValorPagado());
        String email = emailContacto != null ? emailContacto : origen.getEmailContacto();

        Booking nueva;
        try {
            nueva = tx.execute(s -> crear(origen.getId(), hold, pasajeros, precio.unitario(), diferencia, valorPagado,
                    email));
        } catch (DataIntegrityViolationException e) {
            // Dos solicitudes simultáneas: la misma retención o dos reprogramaciones vivas de la misma reserva.
            Booking ganadora = bookingRepository.findByHoldId(holdId).orElse(null);
            if (ganadora == null) {
                throw ApiException.conflict(EN_CURSO, EN_CURSO_MSG);
            }
            return yaCreada(origen, ganadora, viewer, EN_CURSO, EN_CURSO_MSG);
        }
        if (Objects.requireNonNull(nueva).getEstado() == BookingStatus.PAGO_APROBADO) {
            // Sin diferencia que pagar: se confirma de inmediato (asiento nuevo, tiquete nuevo, viejo REPROGRAMADO).
            paymentService.confirmAndIssue(nueva.getId());
        }
        return new BookingService.CreateResult(bookingService.get(nueva.getId(), viewer), true);
    }

    /** Lo que se cobra: la diferencia del pasaje (nunca negativa: sin reembolso) más el cargo fijo. */
    static BigDecimal diferencia(BigDecimal pasajeNuevo, BigDecimal yaPagado, BigDecimal cargo) {
        return pasajeNuevo.subtract(yaPagado).max(BigDecimal.ZERO).add(cargo);
    }

    private Booking propia(UUID bookingId, BookingViewer viewer) {
        Booking booking = bookingRepository.findById(bookingId)
                .filter(viewer::canSee)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "La reserva no existe"));
        if (!booking.getUserId().equals(viewer.userId())) {
            throw ApiException.forbidden("NOT_BOOKING_OWNER", "Solo el dueño de la reserva puede reprogramarla");
        }
        return booking;
    }

    private BookingService.CreateResult yaCreada(Booking origen, Booking existente, BookingViewer viewer,
                                                 String code, String message) {
        if (!origen.getId().equals(existente.getOrigenBookingId())) {
            throw ApiException.conflict(code, message);
        }
        return new BookingService.CreateResult(bookingService.get(existente.getId(), viewer), false);
    }

    /**
     * Reserva CONFIRMADA con tiquete EMITIDO y sin otra reprogramación viva; si es VOLUNTARIA, además dentro del
     * límite de cambios y del plazo. Devuelve su viaje.
     */
    private CatalogClient.ItinerarySummary validarOrigen(Booking origen, Modo modo) {
        if (origen.getEstado() != BookingStatus.CONFIRMADA) {
            throw new ApiException(HttpStatus.CONFLICT, "BOOKING_NOT_RESCHEDULABLE",
                    "Solo se puede reprogramar una reserva confirmada", Map.of(ESTADO, origen.getEstado().name()));
        }
        String estadoTiquete = ticketRepository.findByBookingId(origen.getId()).map(Ticket::getEstado).orElse(null);
        if (!TicketService.EMITIDO.equals(estadoTiquete)) {
            throw new ApiException(HttpStatus.CONFLICT, "TICKET_NOT_RESCHEDULABLE",
                    "El tiquete ya no se puede reprogramar", Map.of(ESTADO, String.valueOf(estadoTiquete)));
        }
        if (modo == Modo.VOLUNTARIA && reprogramaciones(origen) >= properties.maxReschedules()) {
            throw new ApiException(HttpStatus.CONFLICT, "RESCHEDULE_LIMIT_REACHED",
                    "El tiquete ya se reprogramó el máximo de veces permitido",
                    Map.of("maximo", properties.maxReschedules()));
        }
        if (bookingRepository.existsByOrigenBookingIdAndEstadoIn(origen.getId(), VIVAS)) {
            throw ApiException.conflict(EN_CURSO, EN_CURSO_MSG);
        }
        CatalogClient.ItinerarySummary viaje = catalog.itinerary(origen.getItineraryId());
        if (viaje == null || viaje.fechaSalida() == null) {
            throw ApiException.unavailable(CATALOG_UNAVAILABLE, "No se pudo consultar el viaje; intenta de nuevo");
        }
        if (modo == Modo.VOLUNTARIA
                && !viaje.fechaSalida().isAfter(clock.instant().plus(properties.rescheduleCutoff()))) {
            throw new ApiException(HttpStatus.CONFLICT, "RESCHEDULE_WINDOW_CLOSED",
                    "Ya no se puede reprogramar: faltan menos de " + properties.rescheduleCutoff().toHours()
                            + " h para la salida", Map.of("fechaSalida", viaje.fechaSalida().toString()));
        }
        return viaje;
    }

    /** Reprogramaciones ya hechas en la cadena que termina en {@code booking} (0 en una compra normal). */
    int reprogramaciones(Booking booking) {
        int hechas = 0;
        UUID anterior = booking.getOrigenBookingId();
        while (anterior != null && hechas <= properties.maxReschedules()) {
            hechas++;
            anterior = bookingRepository.findById(anterior).map(Booking::getOrigenBookingId).orElse(null);
        }
        return hechas;
    }

    private SeatInventoryClient.Hold validarRetencion(Booking origen, UUID holdId, int pasajeros) {
        SeatInventoryClient.Hold hold = seatInventory.getHold(holdId);
        if (!origen.getUserId().equals(hold.userId())) {
            throw ApiException.conflict("HOLD_NOT_OWNED", "La retención no es del dueño de la reserva");
        }
        if (!"ACTIVA".equals(hold.estado()) || hold.segundosRestantes() <= 0) {
            throw ApiException.conflict("HOLD_NOT_ACTIVE", "La retención de los asientos nuevos ya no está activa");
        }
        if (hold.seatNumbers().size() != pasajeros) {
            throw new ApiException(HttpStatus.CONFLICT, "SEATS_PASSENGERS_MISMATCH",
                    "La retención debe tener un asiento por pasajero (" + pasajeros + ")",
                    Map.of("asientos", hold.seatNumbers().size(), "pasajeros", pasajeros));
        }
        if (hold.itineraryId().equals(origen.getItineraryId())) {
            throw ApiException.conflict("SAME_ITINERARY",
                    "La retención es del mismo viaje: para cambiar de asiento usa POST /tickets/{id}/seat");
        }
        return hold;
    }

    /**
     * Viaje nuevo programado, en el futuro y con el mismo origen y destino; en una VOLUNTARIA, además de la misma
     * empresa (en una AFECTADA puede ser de cualquiera).
     */
    private void validarViajeNuevo(CatalogClient.ItinerarySummary original, Booking origen,
                                   SeatInventoryClient.Hold hold, Modo modo) {
        CatalogClient.ItinerarySummary nuevo = catalog.itinerary(hold.itineraryId());
        if (nuevo == null) {
            throw ApiException.unavailable(CATALOG_UNAVAILABLE, "No se pudo consultar el viaje nuevo; intenta de nuevo");
        }
        if ("CANCELADO".equals(nuevo.estado())) {
            throw ApiException.conflict("ITINERARY_NOT_AVAILABLE", "El viaje nuevo está cancelado");
        }
        if (nuevo.fechaSalida() == null || !nuevo.fechaSalida().isAfter(clock.instant())) {
            throw ApiException.conflict("ITINERARY_DEPARTED", "El viaje nuevo ya salió");
        }
        if (!mismoTexto(original.origen(), nuevo.origen()) || !mismoTexto(original.destino(), nuevo.destino())) {
            throw ApiException.conflict("ROUTE_MISMATCH", "El viaje nuevo debe tener el mismo origen y destino");
        }
        if (modo == Modo.AFECTADA) {
            return;
        }
        UUID empresa = original.companyId() != null ? original.companyId() : origen.getCompanyId();
        if (empresa == null || !empresa.equals(nuevo.companyId())) {
            throw ApiException.conflict("COMPANY_MISMATCH", "El viaje nuevo debe ser de la misma empresa");
        }
    }

    private static boolean mismoTexto(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    /** Pasajeros en el orden de los asientos de la reserva: el i-ésimo recibe el i-ésimo asiento nuevo. */
    private List<Passenger> pasajerosEnOrden(Booking origen) {
        List<String> asientos = origen.getSeatNumbers();
        return passengerRepository.findByBookingId(origen.getId()).stream()
                .sorted(Comparator.comparingInt(p -> asientos.indexOf(p.getAsiento())))
                .toList();
    }

    private Booking crear(UUID origenId, SeatInventoryClient.Hold hold, List<Passenger> pasajeros,
                          BigDecimal precioUnitario, BigDecimal diferencia, BigDecimal valorPagado, String email) {
        // Se bloquea la original y se revalida: dos solicitudes simultáneas nunca crean dos reprogramaciones.
        Booking origen = bookingRepository.lockById(origenId).orElseThrow();
        if (origen.getEstado() != BookingStatus.CONFIRMADA
                || bookingRepository.existsByOrigenBookingIdAndEstadoIn(origenId, VIVAS)) {
            throw ApiException.conflict(EN_CURSO, EN_CURSO_MSG);
        }
        Instant now = clock.instant();
        Booking nueva = bookingRepository.save(Booking.reprogramacion(origen,
                new Booking.SeatSelection(hold.holdId(), hold.itineraryId(), hold.offerId(), hold.seatNumbers(),
                        hold.expiraEn()),
                precioUnitario, diferencia, valorPagado, email, now));
        for (int i = 0; i < pasajeros.size(); i++) {
            Passenger p = pasajeros.get(i);
            passengerRepository.save(new Passenger(nueva.getId(), p.getNombre(), p.getTipoDocumento(),
                    p.getNumeroDocumento(), hold.seatNumbers().get(i)));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", nueva.getId());
        payload.put("origenBookingId", origenId);
        payload.put("holdId", nueva.getHoldId());
        payload.put("itineraryId", nueva.getItineraryId());
        payload.put("userId", nueva.getUserId());
        payload.put("total", nueva.getTotal());
        payload.put("offerId", nueva.getOfferId());
        saga.log(nueva.getId(), "REPROGRAMACION_SOLICITADA", "OK", payload);
        saga.log(origenId, "REPROGRAMACION_SOLICITADA", "OK", payload);
        events.publishAfterCommit(BookingEvents.BOOKING_CREATED, payload);
        return nueva;
    }
}
