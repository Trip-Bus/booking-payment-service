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
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

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
        this.id = UUID.randomUUID();
        this.holdId = holdId;
        this.userId = userId;
        this.itineraryId = itineraryId;
        this.offerId = offerId;
        this.seatNumbers = String.join(",", seatNumbers);
        this.precioUnitario = precioUnitario;
        this.total = precioUnitario.multiply(BigDecimal.valueOf(seatNumbers.size()));
        this.moneda = moneda;
        this.holdExpiraEn = holdExpiraEn;
        this.estado = BookingStatus.PENDIENTE_PAGO;
        this.creadoEn = now;
        this.actualizadoEn = now;
    }

    public void cambiarEstado(BookingStatus nuevo, Instant now) {
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

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
