package com.tripbus.booking.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** US-150: retención (POST /seats/holds) con los asientos del viaje nuevo. */
public record RescheduleRequest(@NotNull(message = "holdId es obligatorio") UUID holdId) {
}
