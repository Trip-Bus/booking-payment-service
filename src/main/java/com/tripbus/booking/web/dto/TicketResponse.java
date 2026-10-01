package com.tripbus.booking.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** US-033: tiquete electrónico. qrImage es un data URI PNG listo para mostrar en un <img>. */
public record TicketResponse(
        UUID ticketId,
        UUID bookingId,
        String estado,
        Instant emitidoEn,
        UUID itineraryId,
        String origen,
        String destino,
        Instant fechaSalida,
        String empresa,
        String placa,
        List<String> seatNumbers,
        List<PassengerResponse> pasajeros,
        BigDecimal total,
        String moneda,
        String qrCode,
        String qrImage) {
}
