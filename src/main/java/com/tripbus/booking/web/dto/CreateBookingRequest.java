package com.tripbus.booking.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** US-031: datos del viajero para la retención activa. Un viajero por asiento retenido. */
public record CreateBookingRequest(
        @NotNull(message = "holdId es obligatorio")
        UUID holdId,

        @NotEmpty(message = "Debe registrar los datos de al menos un viajero")
        List<@Valid PassengerRequest> pasajeros,

        /* US-147: código del cupón de descuento (opcional, sin distinguir mayúsculas). */
        @Size(max = 30, message = "El cupón no puede superar 30 caracteres")
        String cupon) {

    public CreateBookingRequest(UUID holdId, List<PassengerRequest> pasajeros) {
        this(holdId, pasajeros, null);
    }
}
