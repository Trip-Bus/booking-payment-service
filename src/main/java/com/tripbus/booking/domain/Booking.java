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
     * Transiciones permitidas de la saga de compra. Los estados finales (CONFIRMADA, PAGO_RECHAZADO,
     * EXPIRADA, REEMBOLSADA) no tienen salida.
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

    public String getEmailContacto() {
        return emailContacto;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
