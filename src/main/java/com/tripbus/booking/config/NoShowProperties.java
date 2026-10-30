package com.tripbus.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * US-153: un tiquete EMITIDO pasa a NO_SHOW cuando su viaje salió hace más de {@code noShowAfter} (30 min). Se puede
 * reprogramar si {@code noShowRescheduleEnabled} (true), dentro de {@code noShowRescheduleWindow} (24 h) después de la
 * salida, con un cargo de {@code noShowFeePercent} (20 %) del total pagado además de la diferencia de precio.
 */
@ConfigurationProperties(prefix = "tripbus.booking")
public record NoShowProperties(Duration noShowAfter, Boolean noShowRescheduleEnabled,
                               Duration noShowRescheduleWindow, BigDecimal noShowFeePercent) {

    public NoShowProperties {
        noShowAfter = noShowAfter != null ? noShowAfter : Duration.ofMinutes(30);
        noShowRescheduleEnabled = noShowRescheduleEnabled == null || noShowRescheduleEnabled;
        noShowRescheduleWindow = noShowRescheduleWindow != null ? noShowRescheduleWindow : Duration.ofHours(24);
        noShowFeePercent = noShowFeePercent != null ? noShowFeePercent : BigDecimal.valueOf(20);
    }

    /** Valores por defecto (pruebas y lugares sin configuración). */
    public static NoShowProperties defaults() {
        return new NoShowProperties(null, null, null, null);
    }
}
