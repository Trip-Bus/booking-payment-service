package com.tripbus.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * US-154/US-155: un retraso afecta a las reservas si es de al menos {@code affectedDelayMin} minutos (60); el pasajero
 * tiene {@code affectedDecisionWindow} (24 h) para decidir antes de que decida el sistema.
 */
@ConfigurationProperties(prefix = "tripbus.booking")
public record AffectedProperties(Integer affectedDelayMin, Duration affectedDecisionWindow) {

    public AffectedProperties {
        affectedDelayMin = affectedDelayMin != null ? affectedDelayMin : 60;
        affectedDecisionWindow = affectedDecisionWindow != null ? affectedDecisionWindow : Duration.ofHours(24);
    }
}
