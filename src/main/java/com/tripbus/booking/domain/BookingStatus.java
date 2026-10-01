package com.tripbus.booking.domain;

/** Estados de la saga de compra. */
public enum BookingStatus {
    PENDIENTE_PAGO,
    PAGO_EN_PROCESO,
    PAGO_APROBADO,
    CONFIRMADA,
    PAGO_RECHAZADO,
    EXPIRADA,
    REEMBOLSADA
}
