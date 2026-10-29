package com.tripbus.booking.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentResponse(UUID paymentId, UUID bookingId, String estado, BigDecimal monto, String moneda,
                              String mensaje, String redirectUrl) {
    public PaymentResponse(UUID paymentId, UUID bookingId, String estado, BigDecimal monto, String moneda, String mensaje) {
        this(paymentId, bookingId, estado, monto, moneda, mensaje, null);
    }
}
