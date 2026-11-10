package com.tripbus.booking.web.dto;

/**
 * US-190: una fila del reporte de ventas (un grupo o los totales), en COP enteros.
 * ingresosBrutos es lo cobrado (ya con el descuento del cupón); descuentoCupones (US-147) es lo que se descontó con
 * cupones en esas ventas. ingresosNetos = ingresosBrutos - reembolsos; netoEmpresa = ingresosNetos - comisionTripBus.
 * ocupacionPromedio (en %) solo con agrupar=ruta; null si seat-inventory no respondió.
 */
public record SalesReportRow(
        String grupo,
        long tiquetesVendidos,
        long ingresosBrutos,
        long descuentoCupones,
        long reembolsos,
        long ingresosNetos,
        long comisionTripBus,
        long netoEmpresa,
        Double ocupacionPromedio) {
}
