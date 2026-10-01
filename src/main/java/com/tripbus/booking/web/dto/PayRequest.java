package com.tripbus.booking.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * US-032: pago con tarjeta. El frontend tokeniza la tarjeta con la pasarela: aquí solo llega
 * el token (nunca el número completo). En el sandbox use tok_visa_approved / tok_visa_declined.
 */
public record PayRequest(
        @NotBlank(message = "El token de la tarjeta es obligatorio")
        @Size(max = 120)
        String cardToken,

        @Pattern(regexp = "\\d{4}", message = "cardLast4 deben ser 4 dígitos")
        String cardLast4,

        @Size(max = 20)
        String cardBrand) {
}
