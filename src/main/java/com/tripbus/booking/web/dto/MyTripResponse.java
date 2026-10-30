package com.tripbus.booking.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-145: un viaje en "mis viajes". estadoTiquete: EMITIDO, USADO, REPROGRAMADO, ANULADO o NO_SHOW (null si aún no
 * hay tiquete). puedeReprogramar y puedeCambiarAsiento dicen si hoy se permitiría (la regla de la tarifa de oferta
 * se valida con flash-offers al reprogramar).
 */
public record MyTripResponse(
        UUID bookingId,
        String estado,
        String canal,
        UUID itineraryId,
        String origen,
        String destino,
        Instant fechaSalida,
        String empresa,
        List<String> seatNumbers,
        UUID ticketId,
        String estadoTiquete,
        BigDecimal total,
        String moneda,
        boolean puedeReprogramar,
        boolean puedeCambiarAsiento) {
}
