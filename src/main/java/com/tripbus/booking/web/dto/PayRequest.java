package com.tripbus.booking.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * US-032 / US-143: pago con tarjeta, PSE o Nequi. Con tarjeta el frontend la tokeniza con la pasarela: aquí solo
 * llega el token (nunca el número completo). En el sandbox use tok_visa_approved / tok_visa_declined.
 * Las reglas por método (campos obligatorios, banco, celular) las aplica PaymentService; aquí solo se acotan
 * los tamaños para que ningún campo libre llegue sin límite a la base de datos o a la pasarela.
 */
public record PayRequest(
        @Size(max = 20) String metodo,

        @Size(max = 120) String cardToken,

        @Pattern(regexp = "\\d{4}", message = "cardLast4 deben ser 4 dígitos")
        String cardLast4,

        @Size(max = 20) String cardBrand,
        @Valid PseData pse,
        @Valid NequiData nequi) {

    public record PseData(@Size(max = 10) String bancoCodigo, @Size(max = 20) String tipoPersona,
                          @Size(max = 10) String tipoDocumento, @Size(max = 20) String numeroDocumento) { }

    public record NequiData(@Size(max = 10) String telefono) { }

    public String method() {
        return metodo == null || metodo.isBlank() ? "TARJETA" : metodo.trim().toUpperCase(Locale.ROOT);
    }
}
