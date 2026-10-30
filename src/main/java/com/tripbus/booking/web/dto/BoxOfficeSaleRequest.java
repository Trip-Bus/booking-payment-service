package com.tripbus.booking.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** US-144: venta en taquilla con pago en efectivo. Un pasajero por asiento, en el mismo orden. */
public record BoxOfficeSaleRequest(
        @NotNull(message = "El viaje es obligatorio")
        UUID itineraryId,

        @NotEmpty(message = "Debe elegir al menos un asiento")
        @Size(max = 10, message = "Máximo 10 asientos por venta")
        List<@NotBlank @Size(max = 10) String> seatNumbers,

        @NotEmpty(message = "Debe registrar los pasajeros")
        @Size(max = 10, message = "Máximo 10 pasajeros por venta")
        List<@NotNull @Valid PassengerRequest> pasajeros,

        @NotNull(message = "Los datos del comprador son obligatorios")
        @Valid
        Comprador comprador) {

    /** Quien paga en taquilla. Si su correo es de un pasajero registrado, la reserva queda a su nombre. */
    public record Comprador(
            @NotBlank(message = "El nombre del comprador es obligatorio")
            @Size(max = 160)
            String nombre,

            @Email(message = "Correo inválido")
            @Size(max = 320)
            String email,

            @Pattern(regexp = "\\+?[0-9 ]{7,20}", message = "Teléfono inválido")
            String telefono) {
    }
}
