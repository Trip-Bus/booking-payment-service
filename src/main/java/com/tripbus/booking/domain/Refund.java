package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(nullable = false)
    private BigDecimal monto;

    @Column(nullable = false)
    private String motivo;

    @Column(nullable = false)
    private String estado;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    /** US-190: comisión de la venta revertida, en negativo; null si la venta aún no tenía comisión (la completa el job). */
    @Column(name = "comision_valor")
    private BigDecimal comisionValor;

    protected Refund() {
    }

    /** Reembolso de una venta: revierte su comisión de TripBus (queda en negativo). */
    public static Refund de(Payment payment, BigDecimal monto, String motivo, Instant now) {
        Refund refund = new Refund(payment.getId(), monto, motivo, now);
        refund.comisionValor = payment.getComisionValor() != null ? payment.getComisionValor().negate() : null;
        return refund;
    }

    public Refund(UUID paymentId, BigDecimal monto, String motivo, Instant now) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.monto = monto;
        this.motivo = motivo;
        this.estado = "PROCESADO";
        this.creadoEn = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public BigDecimal getComisionValor() {
        return comisionValor;
    }
}
