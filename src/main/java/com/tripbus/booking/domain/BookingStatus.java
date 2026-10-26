package com.tripbus.booking.domain;

/** Estados de la saga de compra. */
public enum BookingStatus {
    PENDIENTE_PAGO,
    PAGO_EN_PROCESO,
    PAGO_APROBADO,
    CONFIRMADA,
    PAGO_RECHAZADO,
    EXPIRADA,
    REEMBOLSADA,
    /** US-150: la reserva se cambió por otra (origen_booking_id de la nueva); su tiquete quedó REPROGRAMADO. */
    REPROGRAMADA
}
