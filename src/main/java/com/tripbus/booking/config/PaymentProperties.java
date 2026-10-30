package com.tripbus.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/** Pasarela de pagos sandbox del MVP (tarjeta, PSE y Nequi). */
@ConfigurationProperties(prefix = "tripbus.payment")
public record PaymentProperties(
        String webhookUrl,
        String webhookSecret,
        Duration webhookDelay, boolean duplicateWebhook, Duration nequiDelay, Duration pseTimeout,
        boolean sandboxPagesEnabled, String frontendUrl) {

    // Hay un segundo constructor (compatibilidad): Spring debe enlazar con el canónico.
    @ConstructorBinding
    public PaymentProperties {
        webhookDelay = webhookDelay != null ? webhookDelay : Duration.ofMillis(1500);
        nequiDelay = nequiDelay != null ? nequiDelay : Duration.ofSeconds(5);
        pseTimeout = pseTimeout != null ? pseTimeout : Duration.ofMinutes(10);
        frontendUrl = frontendUrl != null ? frontendUrl : "http://localhost:5173";
    }

    public PaymentProperties(String webhookUrl, String webhookSecret, Duration webhookDelay, boolean duplicateWebhook) {
        this(webhookUrl, webhookSecret, webhookDelay, duplicateWebhook, null, null, true, null);
    }
}
