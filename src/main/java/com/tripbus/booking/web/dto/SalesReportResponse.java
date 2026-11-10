package com.tripbus.booking.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * US-190: GET /reports/sales. companyId null = todas las empresas (solo ADMIN). ventasSinComision: ventas del rango
 * cuya comisión aún no se registró (catalog no respondió); CommissionJob la completa.
 */
public record SalesReportResponse(
        LocalDate desde,
        LocalDate hasta,
        String agrupar,
        UUID companyId,
        List<SalesReportRow> grupos,
        SalesReportRow totales,
        long ventasSinComision) {
}
