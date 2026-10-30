package com.tripbus.booking.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * US-144: cierre de caja de un día (hora de Colombia). vendedor es null cuando el ADMIN ve todas las ventas.
 * totalEfectivo suma los pagos en efectivo vigentes; totalReembolsado, los que se devolvieron después (p. ej. la
 * empresa canceló el viaje).
 */
public record CashClosingResponse(
        LocalDate fecha,
        UUID vendedor,
        int cantidadVentas,
        int cantidadTiquetes,
        int cantidadAsientos,
        BigDecimal totalEfectivo,
        BigDecimal totalReembolsado,
        List<Venta> ventas) {

    public record Venta(
            UUID bookingId,
            String estado,
            UUID ticketId,
            String estadoTiquete,
            UUID vendidoPor,
            Instant vendidoEn,
            UUID itineraryId,
            String origen,
            String destino,
            Instant fechaSalida,
            List<String> seatNumbers,
            String compradorNombre,
            BigDecimal total,
            String moneda,
            String estadoPago) {
    }
}
