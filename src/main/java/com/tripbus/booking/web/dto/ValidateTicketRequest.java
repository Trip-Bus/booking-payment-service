package com.tripbus.booking.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ValidateTicketRequest(@NotBlank(message = "El contenido del QR es obligatorio") String qrCode) {
}
