package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    /**
     * Transiciones permitidas de la saga de compra. Los estados finales (PAGO_RECHAZADO, EXPIRADA,
     * REEMBOLSADA, REPROGRAMADA) no tienen salida; CONFIRMADA solo sale por una reprogramación (US-150)
     * o por el reembolso de una reserva afectada por la empresa (US-154/US-155).
     */
    private static final Map<BookingStatus, Set<BookingStatus>> TRANSICIONES = new EnumMap<>(BookingStatus.class);

    static {
        for (BookingStatus estado : BookingStatus.values()) {
            TRANSICIONES.put(estado, EnumSet.noneOf(BookingStatus.class));
        }
        // Se inicia el pago, o la retención vence antes de pagar.
        TRANSICIONES.put(BookingStatus.PENDIENTE_PAGO, EnumSet.of(BookingStatus.PAGO_EN_PROCESO, BookingStatus.EXPIRADA));
        // Webhook aprobado, o rechazo (pasarela, monto incorrecto, pasarela caída o sin respuesta).
        TRANSICIONES.put(BookingStatus.PAGO_EN_PROCESO, EnumSet.of(BookingStatus.PAGO_APROBADO, BookingStatus.PAGO_RECHAZADO));
        // Asiento confirmado y tiquete emitido, o el asiento se perdió y se reembolsa.
        TRANSICIONES.put(BookingStatus.PAGO_APROBADO, EnumSet.of(BookingStatus.CONFIRMADA, BookingStatus.REEMBOLSADA));
        // US-150: se confirmó la reserva nueva que la reemplaza. US-154/US-155: la empresa canceló o cambió el
        // viaje y se reembolsa (AffectedBookingService es el único que lo hace: no hay reembolso voluntario).
        TRANSICIONES.put(BookingStatus.CONFIRMADA, EnumSet.of(BookingStatus.REPROGRAMADA, BookingStatus.REEMBOLSADA));
    }

    @Id
    private UUID id;

    @Column(name = "hold_id", nullable = false, unique = true)
    private UUID holdId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Column(name = "offer_id")
    private UUID offerId;

    @Column(name = "seat_numbers", nullable = false)
    private String seatNumbers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus estado;

    @Column(name = "precio_unitario", nullable = false)
    private BigDecimal precioUnitario;

    @Column(nullable = false)
    private BigDecimal total;

    @Column(nullable = false)
    private String moneda;

    @Column(name = "hold_expira_en")
    private Instant holdExpiraEn;

    /** US-033: correo del comprador (claim "email" del JWT) al que se envía el tiquete; null si no venía. */
    @Column(name = "email_contacto")
    private String emailContacto;

    /** US-005: empresa del itinerario (de catalog); el OPERADOR solo ve las reservas de su empresa. */
    @Column(name = "company_id")
    private UUID companyId;

    /** US-150: reserva de la que viene esta reprogramación (null en una compra normal). */
    @Column(name = "origen_booking_id")
    private UUID origenBookingId;

    /** US-150: valor del pasaje ya pagado en la cadena de reprogramaciones (null en una compra normal). */
    @Column(name = "valor_pagado")
    private BigDecimal valorPagado;

    /** US-150: en una reserva REPROGRAMADA, cuándo quedó resuelta la anulación de sus asientos viejos. */
    @Column(name = "anulacion_resuelta_en")
    private Instant anulacionResueltaEn;

    /** US-145: datos del viaje copiados de catalog (null mientras TripDataBackfill no los rellene). */
    @Column(name = "origen")
    private String origen;

    @Column(name = "destino")
    private String destino;

    /** Salida del viaje; ItineraryRescheduled la actualiza. */
    @Column(name = "fecha_salida")
    private Instant fechaSalida;

    @Column(name = "empresa_nombre")
    private String empresaNombre;

    /** US-144: APP (compra del pasajero) o TAQUILLA (venta en efectivo de un operador). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Canal canal = Canal.APP;

    /** US-144: operador que hizo la venta en taquilla (null en la app). */
    @Column(name = "vendido_por")
    private UUID vendidoPor;

    @Column(name = "comprador_nombre")
    private String compradorNombre;

    @Column(name = "comprador_email")
    private String compradorEmail;

    @Column(name = "comprador_telefono")
    private String compradorTelefono;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Version
    private long version;

    protected Booking() {
    }

    public Booking(UUID holdId, UUID userId, UUID itineraryId, UUID offerId, List<String> seatNumbers,
                   BigDecimal precioUnitario, String moneda, Instant holdExpiraEn, Instant now) {
        this(holdId, userId, itineraryId, offerId, seatNumbers, precioUnitario, moneda, holdExpiraEn, null, now);
    }

    public Booking(UUID holdId, UUID userId, UUID itineraryId, UUID offerId, List<String> seatNumbers,
                   BigDecimal precioUnitario, String moneda, Instant holdExpiraEn, String emailContacto, Instant now) {
        this(holdId, userId, itineraryId, offerId, seatNumbers, precioUnitario, moneda, holdExpiraEn, emailContacto,
                null, now);
    }

    public Booking(UUID holdId, UUID userId, UUID itineraryId, UUID offerId, List<String> seatNumbers,
                   BigDecimal precioUnitario, String moneda, Instant holdExpiraEn, String emailContacto,
                   UUID companyId, Instant now) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.holdId = holdId;
        this.userId = userId;
        this.itineraryId = itineraryId;
        this.offerId = offerId;
        this.seatNumbers = String.join(",", seatNumbers);
        this.precioUnitario = precioUnitario;
        this.total = precioUnitario.multiply(BigDecimal.valueOf(seatNumbers.size()));
        this.moneda = moneda;
        this.holdExpiraEn = holdExpiraEn;
        this.emailContacto = emailContacto;
        this.estado = BookingStatus.PENDIENTE_PAGO;
        this.creadoEn = now;
        this.actualizadoEn = now;
    }

    /**
     * US-150: reserva nueva que reemplaza a {@code origen} con los asientos de otra retención. {@code total} es lo
     * que falta pagar (la diferencia más el cargo); si es cero no hay nada que cobrar y la reserva nace en
     * PAGO_APROBADO, lista para confirmar el asiento y emitir el tiquete. {@code valorPagado} es el pasaje que
     * queda pagado en la cadena (nunca baja: no hay reembolso por decisión del pasajero).
     */
    public static Booking reprogramacion(Booking origen, SeatSelection seleccion, BigDecimal precioUnitario,
                                         BigDecimal total, BigDecimal valorPagado, String emailContacto, Instant now) {
        Booking b = new Booking(seleccion.holdId(), origen.getUserId(), seleccion.itineraryId(), seleccion.offerId(),
                seleccion.seatNumbers(), precioUnitario, origen.getMoneda(), seleccion.holdExpiraEn(), emailContacto,
                origen.getCompanyId(), now);
        b.origenBookingId = origen.getId();
        b.total = total;
        b.valorPagado = valorPagado;
        if (total.signum() == 0) {
            b.estado = BookingStatus.PAGO_APROBADO;
        }
        return b;
    }

    /** Asientos retenidos con los que se arma una reserva (los datos de la retención de seat-inventory). */
    public record SeatSelection(UUID holdId, UUID itineraryId, UUID offerId, List<String> seatNumbers,
                                Instant holdExpiraEn) {
    }

    public enum Canal { APP, TAQUILLA }

    /** US-145: datos del viaje que se guardan en la reserva. */
    public record Viaje(String origen, String destino, Instant fechaSalida, String empresa) {
    }

    /** US-144: quién vendió en taquilla y los datos que dio el comprador (email y teléfono opcionales). */
    public record VentaTaquilla(UUID vendidoPor, String compradorNombre, String compradorEmail,
                                String compradorTelefono) {
    }

    /**
     * US-144: venta en taquilla. La reserva queda a nombre de {@code userId} (el pasajero registrado con el correo
     * del comprador, o el operador) y el tiquete se envía al correo del comprador si lo dio.
     */
    public static Booking taquilla(SeatSelection seleccion, UUID userId, VentaTaquilla venta, BigDecimal precioUnitario,
                                   String moneda, UUID companyId, Instant now) {
        Booking b = new Booking(seleccion.holdId(), userId, seleccion.itineraryId(), seleccion.offerId(),
                seleccion.seatNumbers(), precioUnitario, moneda, seleccion.holdExpiraEn(), venta.compradorEmail(),
                companyId, now);
        b.canal = Canal.TAQUILLA;
        b.vendidoPor = venta.vendidoPor();
        b.compradorNombre = venta.compradorNombre();
        b.compradorEmail = venta.compradorEmail();
        b.compradorTelefono = venta.compradorTelefono();
        return b;
    }

    /** US-145: copia los datos del viaje (los null no reemplazan lo que ya había). */
    public void asignarViaje(Viaje viaje) {
        if (viaje == null) {
            return;
        }
        origen = viaje.origen() != null ? viaje.origen() : origen;
        destino = viaje.destino() != null ? viaje.destino() : destino;
        fechaSalida = viaje.fechaSalida() != null ? viaje.fechaSalida() : fechaSalida;
        empresaNombre = viaje.empresa() != null ? viaje.empresa() : empresaNombre;
    }

    /** US-139: cambia {@code desde} por {@code hacia} en los asientos de la reserva (conserva el orden). */
    public void cambiarAsiento(String desde, String hacia, Instant now) {
        List<String> asientos = new ArrayList<>(getSeatNumbers());
        int i = asientos.indexOf(desde);
        if (i < 0) {
            throw new IllegalArgumentException("El asiento " + desde + " no es de la reserva " + id);
        }
        asientos.set(i, hacia);
        this.seatNumbers = String.join(",", asientos);
        this.actualizadoEn = now;
    }

    /** US-150: seat-inventory ya respondió a la anulación de los asientos viejos (no queda nada que reintentar). */
    public void marcarAnulacionResuelta(Instant now) {
        this.anulacionResueltaEn = now;
        this.actualizadoEn = now;
    }

    /** Cambia el estado si la transición está permitida; si no, lanza {@link InvalidBookingTransitionException}. */
    public void cambiarEstado(BookingStatus nuevo, Instant now) {
        if (!puedeCambiarA(nuevo)) {
            throw new InvalidBookingTransitionException(estado, nuevo);
        }
        this.estado = nuevo;
        this.actualizadoEn = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public List<String> getSeatNumbers() {
        return Arrays.asList(seatNumbers.split(","));
    }

    public BookingStatus getEstado() {
        return estado;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getMoneda() {
        return moneda;
    }

    public Instant getHoldExpiraEn() {
        return holdExpiraEn;
    }

    public boolean puedeCambiarA(BookingStatus nuevo) {
        return TRANSICIONES.get(estado).contains(nuevo);
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getOrigenBookingId() {
        return origenBookingId;
    }

    /** Valor del pasaje ya pagado: en una reprogramación el acumulado de la cadena; si no, el total. */
    public BigDecimal getValorPagado() {
        return valorPagado != null ? valorPagado : total;
    }

    public Instant getAnulacionResueltaEn() {
        return anulacionResueltaEn;
    }

    public String getEmailContacto() {
        return emailContacto;
    }

    public String getOrigen() {
        return origen;
    }

    public String getDestino() {
        return destino;
    }

    public Instant getFechaSalida() {
        return fechaSalida;
    }

    public String getEmpresaNombre() {
        return empresaNombre;
    }

    public Canal getCanal() {
        return canal;
    }

    public UUID getVendidoPor() {
        return vendidoPor;
    }

    public String getCompradorNombre() {
        return compradorNombre;
    }

    public String getCompradorEmail() {
        return compradorEmail;
    }

    public String getCompradorTelefono() {
        return compradorTelefono;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
