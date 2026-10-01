package com.tripbus.booking.web.dto;

import java.time.Instant;

public record SagaStepResponse(String paso, String estado, String payload, Instant creadoEn) {
}
