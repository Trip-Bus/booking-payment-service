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
import java.util.UUID;

/** Pago con tarjeta. Nunca se guarda el número completo: solo token de la pasarela y últimos 4. */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(nullable = false)
    private BigDecimal monto;

    @Column(nullable = false)
    private String metodo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus estado;

    @Column(name = "payment_gateway_id", unique = true)
    private String paymentGatewayId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "tarjeta_ultimos4")
    private String tarjetaUltimos4;

    @Column(name = "tarjeta_marca")
    private String tarjetaMarca;

    @Column(name = "motivo_rechazo")
    private String motivoRechazo;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Version
    private long version;

    protected Payment() {
    }

    public Payment(UUID bookingId, BigDecimal monto, String idempotencyKey, String ultimos4, String marca, Instant now) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.monto = monto;
        this.metodo = "TARJETA";
        this.estado = PaymentStatus.PENDIENTE;
        this.idempotencyKey = idempotencyKey;
        this.tarjetaUltimos4 = ultimos4;
        this.tarjetaMarca = marca;
        this.creadoEn = now;
        this.actualizadoEn = now;
    }

    public void asignarGatewayId(String gatewayId, Instant now) {
        this.paymentGatewayId = gatewayId;
        this.actualizadoEn = now;
    }

    public void aprobar(Instant now) {
        this.estado = PaymentStatus.APROBADO;
        this.actualizadoEn = now;
    }

    public void rechazar(String motivo, Instant now) {
        this.estado = PaymentStatus.RECHAZADO;
        this.motivoRechazo = motivo;
        this.actualizadoEn = now;
    }

    public void reembolsar(Instant now) {
        this.estado = PaymentStatus.REEMBOLSADO;
        this.actualizadoEn = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public String getMetodo() {
        return metodo;
    }

    public PaymentStatus getEstado() {
        return estado;
    }

    public String getPaymentGatewayId() {
        return paymentGatewayId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getTarjetaUltimos4() {
        return tarjetaUltimos4;
    }

    public String getTarjetaMarca() {
        return tarjetaMarca;
    }

    public String getMotivoRechazo() {
        return motivoRechazo;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
