package com.tripbus.booking.common.error;

import java.util.Map;

/** Mismo formato de error que el servicio identity: {"error":{"code","message","details"}}. */
public record ErrorResponse(ErrorDetail error) {

    public record ErrorDetail(String code, String message, Map<String, Object> details) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorDetail(code, message, Map.of()));
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(new ErrorDetail(code, message, details));
    }
}
