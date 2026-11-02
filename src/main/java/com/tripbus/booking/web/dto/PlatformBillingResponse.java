package com.tripbus.booking.web.dto;

import java.util.List;

/**
 * US-190: GET /reports/platform?mes=YYYY-MM. Totales de la plataforma; incompleto = true si falta la cuota de alguna
 * empresa (catalog no respondió) y por lo tanto cuotas y total no la incluyen.
 */
public record PlatformBillingResponse(
        String mes,
        List<PlatformBillingRow> empresas,
        long cuotas,
        long comisiones,
        long total,
        long tiquetes,
        boolean incompleto) {
}
