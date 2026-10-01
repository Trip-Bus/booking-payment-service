package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    public static final String EN_PROCESO = "EN_PROCESO";
    public static final String COMPLETADA = "COMPLETADA";

    @Id
    private String key;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(nullable = false)
    private String estado;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    protected IdempotencyKey() {
    }

    public IdempotencyKey(String key, UUID userId, String requestHash, Instant now, Instant expiraEn) {
        this.key = key;
        this.userId = userId;
        this.requestHash = requestHash;
        this.estado = EN_PROCESO;
        this.creadoEn = now;
        this.expiraEn = expiraEn;
    }

    public void completar(int status, String body) {
        this.estado = COMPLETADA;
        this.responseStatus = status;
        this.responseBody = body;
    }

    public String getKey() {
        return key;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getEstado() {
        return estado;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getExpiraEn() {
        return expiraEn;
    }
}
