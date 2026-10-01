package com.tripbus.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "webhook_log")
public class WebhookLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gateway_event_id")
    private String gatewayEventId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    private String firma;

    @Column(nullable = false)
    private boolean valido;

    private String resultado;

    @Column(name = "procesado_en", nullable = false)
    private Instant procesadoEn;

    protected WebhookLog() {
    }

    public WebhookLog(String gatewayEventId, String payload, String firma, boolean valido, String resultado,
                      Instant procesadoEn) {
        this.gatewayEventId = gatewayEventId;
        this.payload = payload;
        this.firma = firma;
        this.valido = valido;
        this.resultado = resultado;
        this.procesadoEn = procesadoEn;
    }
}
