package com.tripbus.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * US-150: {@code rescheduleCutoff} es lo mínimo que debe faltar para la salida del viaje original (3 h),
 * {@code maxReschedules} el máximo de reprogramaciones de toda la cadena (2) y {@code rescheduleFee} el cargo
 * fijo por reprogramar (0).
 */
@ConfigurationProperties(prefix = "tripbus.booking")
public record BookingProperties(
        String seatInventoryUrl,
        String catalogUrl,
        String flashOffersUrl,
        Duration idempotencyTtl,
        String ticketSecret,
        Duration rescheduleCutoff,
        Integer maxReschedules,
        BigDecimal rescheduleFee) {

    @ConstructorBinding
    public BookingProperties {
        idempotencyTtl = idempotencyTtl != null ? idempotencyTtl : Duration.ofHours(24);
        rescheduleCutoff = rescheduleCutoff != null ? rescheduleCutoff : Duration.ofHours(3);
        maxReschedules = maxReschedules != null ? maxReschedules : 2;
        rescheduleFee = rescheduleFee != null ? rescheduleFee : BigDecimal.ZERO;
    }

    public BookingProperties(String seatInventoryUrl, String catalogUrl, String flashOffersUrl,
                             Duration idempotencyTtl, String ticketSecret) {
        this(seatInventoryUrl, catalogUrl, flashOffersUrl, idempotencyTtl, ticketSecret, null, null, null);
    }
}
