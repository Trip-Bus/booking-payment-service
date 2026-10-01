package com.tripbus.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Pasarela de pagos sandbox del MVP (solo tarjeta). */
@ConfigurationProperties(prefix = "tripbus.payment")
public record PaymentProperties(
        String webhookUrl,
        String webhookSecret,
        Duration webhookDelay,
        boolean duplicateWebhook) {

    public PaymentProperties {
        webhookDelay = webhookDelay != null ? webhookDelay : Duration.ofMillis(1500);
    }
}
