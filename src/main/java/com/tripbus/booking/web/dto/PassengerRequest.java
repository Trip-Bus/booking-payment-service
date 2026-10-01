package com.tripbus.booking.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PassengerRequest(
        @NotBlank(message = "El nombre del viajero es obligatorio")
        @Size(max = 160, message = "El nombre no puede superar 160 caracteres")
        String nombre,

        @NotBlank(message = "El tipo de documento es obligatorio")
        @Pattern(regexp = "CC|CE|TI|PA|PEP|NIT", message = "Tipo de documento inválido (CC, CE, TI, PA, PEP o NIT)")
        String tipoDocumento,

        @NotBlank(message = "El número de documento es obligatorio")
        @Pattern(regexp = "[A-Za-z0-9-]{4,20}", message = "Número de documento inválido")
        String numeroDocumento) {
}
