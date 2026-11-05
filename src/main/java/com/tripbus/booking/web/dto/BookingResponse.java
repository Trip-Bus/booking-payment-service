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
        /* US-150: reserva de la que viene esta reprogramación (null en una compra normal). */
        UUID origenBookingId,
        Instant creadoEn,
        Instant actualizadoEn,
        /* US-138/US-141: suma del desglose por pasajero (antes del cupón); total = subtotal - descuentoCupon. */
        BigDecimal subtotal,
        /* US-147: descuento del cupón (0 sin cupón) y su código. */
        BigDecimal descuentoCupon,
        String cuponCodigo,
        /* US-142: tramo comprado; null = el viaje completo. */
        TramoResponse tramo) {

    public BookingResponse(UUID bookingId, String estado, UUID holdId, UUID itineraryId, UUID offerId,
                           List<String> seatNumbers, List<PassengerResponse> pasajeros, BigDecimal precioUnitario,
                           BigDecimal total, String moneda, Instant holdExpiraEn, PaymentSummary pago, UUID ticketId,
                           UUID origenBookingId, Instant creadoEn, Instant actualizadoEn) {
        this(bookingId, estado, holdId, itineraryId, offerId, seatNumbers, pasajeros, precioUnitario, total, moneda,
                holdExpiraEn, pago, ticketId, origenBookingId, creadoEn, actualizadoEn, total, BigDecimal.ZERO, null,
                null);
    }
}
