package com.tripbus.booking.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingResponse(
        UUID bookingId,
        String estado,
        UUID holdId,
        UUID itineraryId,
        UUID offerId,
        List<String> seatNumbers,
        List<PassengerResponse> pasajeros,
        BigDecimal precioUnitario,
        BigDecimal total,
        String moneda,
        Instant holdExpiraEn,
        PaymentSummary pago,
        UUID ticketId,
        Instant creadoEn,
        Instant actualizadoEn) {
}
