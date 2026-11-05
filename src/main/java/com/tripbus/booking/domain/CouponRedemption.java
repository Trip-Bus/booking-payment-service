package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** US-147: uso de un cupón en una reserva. ACTIVO cuenta como uso; LIBERADO no (la reserva venció o el pago falló). */
@Entity
@Table(name = "coupon_redemptions")
public class CouponRedemption {

    public static final String ACTIVO = "ACTIVO";
    public static final String LIBERADO = "LIBERADO";

    @Id
    private UUID id;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private BigDecimal descuento;

    @Column(nullable = false)
    private String estado;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "liberado_en")
    private Instant liberadoEn;

    protected CouponRedemption() {
    }

    public CouponRedemption(UUID couponId, UUID bookingId, UUID userId, BigDecimal descuento, Instant now) {
        this.id = UUID.randomUUID();
        this.couponId = couponId;
        this.bookingId = bookingId;
        this.userId = userId;
        this.descuento = descuento;
        this.estado = ACTIVO;
        this.creadoEn = now;
    }

    public UUID getCouponId() {
        return couponId;
    }

    public String getEstado() {
        return estado;
    }
}
