package com.tripbus.booking.web.dto;

/**
 * US-190: una fila del reporte de ventas (un grupo o los totales), en COP enteros.
 * ingresosNetos = ingresosBrutos - reembolsos; netoEmpresa = ingresosNetos - comisionTripBus.
 * ocupacionPromedio (en %) solo con agrupar=ruta; null si seat-inventory no respondió.
 */
public record SalesReportRow(
        String grupo,
        long tiquetesVendidos,
        long ingresosBrutos,
        long reembolsos,
        long ingresosNetos,
        long comisionTripBus,
        long netoEmpresa,
        Double ocupacionPromedio) {
}
