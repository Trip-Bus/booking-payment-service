package com.tripbus.booking.common.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tripbus.events")
public record EventProperties(
        boolean enabled,
        String stream,
        long maxLen,
        String group,
        boolean consumerEnabled) {

    public EventProperties {
        if (stream == null || stream.isBlank()) {
            stream = "tripbus:events";
        }
        if (maxLen <= 0) {
            maxLen = 100_000;
        }
    }
}
