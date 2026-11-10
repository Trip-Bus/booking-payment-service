package com.tripbus.booking.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** US-139: cambiar fromSeat (de la reserva) por toSeat (libre) en el mismo viaje. */
public record ChangeSeatRequest(
        @NotBlank(message = "fromSeat es obligatorio")
        @Size(max = 10)
        String fromSeat,

        @NotBlank(message = "toSeat es obligatorio")
        @Size(max = 10)
        String toSeat) {
}
