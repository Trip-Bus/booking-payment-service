package com.tripbus.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "tripbus.booking")
public record BookingProperties(
        String seatInventoryUrl,
        String catalogUrl,
        String flashOffersUrl,
        Duration idempotencyTtl,
        String ticketSecret) {

    public BookingProperties {
        idempotencyTtl = idempotencyTtl != null ? idempotencyTtl : Duration.ofHours(24);
    }
}
