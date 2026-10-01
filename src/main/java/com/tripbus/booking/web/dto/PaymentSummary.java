package com.tripbus.booking.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentSummary(UUID paymentId, String estado, BigDecimal monto, String metodo, String tarjetaUltimos4,
                             String motivoRechazo, Instant actualizadoEn) {
}
