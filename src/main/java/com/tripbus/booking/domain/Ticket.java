package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    /** Contenido codificado en el QR (incluye firma HMAC para validarlo al abordar). */
    @Column(name = "qr_code", nullable = false, columnDefinition = "TEXT")
    private String qrCode;

    @Column(name = "qr_token", nullable = false, unique = true)
    private String qrToken;

    @Column(name = "emitido_en", nullable = false)
    private Instant emitidoEn;

    @Column(nullable = false)
    private String estado;

    protected Ticket() {
    }

    public Ticket(UUID id, UUID bookingId, String qrCode, String qrToken, Instant emitidoEn) {
        this.id = id;
        this.bookingId = bookingId;
        this.qrCode = qrCode;
        this.qrToken = qrToken;
        this.emitidoEn = emitidoEn;
        this.estado = "EMITIDO";
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getQrCode() {
        return qrCode;
    }

    public String getQrToken() {
        return qrToken;
    }

    public Instant getEmitidoEn() {
        return emitidoEn;
    }

    public String getEstado() {
        return estado;
    }
}
