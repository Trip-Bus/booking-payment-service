package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Bitácora persistida de la saga de compra (permite retomar tras una caída). */
@Entity
@Table(name = "saga_log")
public class SagaLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(nullable = false)
    private String paso;

    @Column(nullable = false)
    private String estado;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected SagaLogEntry() {
    }

    public SagaLogEntry(UUID bookingId, String paso, String estado, String payload, Instant creadoEn) {
        this.bookingId = bookingId;
        this.paso = paso;
        this.estado = estado;
        this.payload = payload;
        this.creadoEn = creadoEn;
    }

    public Long getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getPaso() {
        return paso;
    }

    public String getEstado() {
        return estado;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
