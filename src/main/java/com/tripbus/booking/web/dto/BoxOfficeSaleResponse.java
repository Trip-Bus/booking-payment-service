package com.tripbus.booking.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-144: resultado de la venta en taquilla. userId es el dueño de la reserva (el pasajero registrado o el operador);
 * compradorRegistrado dice cuál. estado es CONFIRMADA con ticketId; si seat-inventory no respondió al confirmar,
 * PAGO_APROBADO (el job de recuperación la termina) y si el asiento se perdió, REEMBOLSADA (devolver el efectivo).
 */
public record BoxOfficeSaleResponse(
        UUID bookingId,
        String estado,
        UUID ticketId,
        UUID userId,
        boolean compradorRegistrado,
        UUID vendidoPor,
        UUID itineraryId,
        String origen,
        String destino,
        Instant fechaSalida,
        List<String> seatNumbers,
        List<PassengerResponse> pasajeros,
        BigDecimal total,
        String moneda,
        String metodoPago,
        Instant vendidoEn) {
}
