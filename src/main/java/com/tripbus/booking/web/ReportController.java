package com.tripbus.booking.web;

import com.tripbus.booking.service.BookingViewer;
import com.tripbus.booking.service.ReportService;
import com.tripbus.booking.web.dto.PlatformBillingResponse;
import com.tripbus.booking.web.dto.PlatformBillingRow;
import com.tripbus.booking.web.dto.SalesReportResponse;
import com.tripbus.booking.web.dto.SalesReportRow;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * US-190: reportes de ventas e ingresos con la comisión de TripBus. OPERADOR solo ve su empresa; ADMIN todas o la que
 * pida con ?companyId=. La facturación de la plataforma es solo para ADMIN.
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** Ventas por dia, ruta, metodo o canal entre desde y hasta (fechas de Colombia, máximo 366 días). */
    @GetMapping("/sales")
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
    public SalesReportResponse sales(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                     @RequestParam(required = false) String agrupar,
                                     @RequestParam(required = false) UUID companyId,
                                     Authentication authentication) {
        UUID empresa = ReportService.empresaDelReporte(BookingViewer.of(authentication), companyId);
        return reportService.sales(desde, hasta, ReportService.Agrupacion.of(agrupar), empresa);
    }

    @GetMapping("/sales.csv")
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
    public ResponseEntity<String> salesCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                           @RequestParam(required = false) String agrupar,
                                           @RequestParam(required = false) UUID companyId,
                                           Authentication authentication) {
        SalesReportResponse report = sales(desde, hasta, agrupar, companyId, authentication);
        return csv("ventas_" + desde + "_" + hasta + ".csv", salesCsv(report));
    }

    /** Facturación de TripBus del mes (YYYY-MM) por empresa: cuota mensual + comisión. */
    @GetMapping("/platform")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformBillingResponse platform(@RequestParam String mes) {
        return reportService.platform(ReportService.mes(mes));
    }

    @GetMapping("/platform.csv")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> platformCsv(@RequestParam String mes) {
        PlatformBillingResponse report = platform(mes);
        return csv("plataforma_" + report.mes() + ".csv", platformCsv(report));
    }

    private static ResponseEntity<String> csv(String archivo, String contenido) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + archivo + "\"")
                .body(contenido);
    }

    static String salesCsv(SalesReportResponse report) {
        boolean conOcupacion = "ruta".equals(report.agrupar());
        StringBuilder sb = new StringBuilder(
                "grupo,tiquetes_vendidos,ingresos_brutos,descuento_cupones,reembolsos,ingresos_netos,comision_tripbus,"
                        + "neto_empresa");
        sb.append(conOcupacion ? ",ocupacion_promedio\n" : "\n");
        for (SalesReportRow fila : report.grupos()) {
            salesCsvRow(sb, fila, conOcupacion);
        }
        salesCsvRow(sb, report.totales(), conOcupacion);
        return sb.toString();
    }

    private static void salesCsvRow(StringBuilder sb, SalesReportRow f, boolean conOcupacion) {
        sb.append(campo(f.grupo())).append(',').append(f.tiquetesVendidos()).append(',').append(f.ingresosBrutos())
                .append(',').append(f.descuentoCupones())
                .append(',').append(f.reembolsos()).append(',').append(f.ingresosNetos()).append(',')
                .append(f.comisionTripBus()).append(',').append(f.netoEmpresa());
        if (conOcupacion) {
            sb.append(',').append(f.ocupacionPromedio() == null ? ""
                    : String.format(Locale.ROOT, "%.2f", f.ocupacionPromedio()));
        }
        sb.append('\n');
    }

    static String platformCsv(PlatformBillingResponse report) {
        StringBuilder sb = new StringBuilder("company_id,empresa,cuota_mensual,comision,total_a_cobrar,tiquetes\n");
        for (PlatformBillingRow f : report.empresas()) {
            sb.append(f.companyId()).append(',').append(campo(f.empresa())).append(',')
                    .append(f.cuotaMensual() == null ? "" : f.cuotaMensual()).append(',').append(f.comision())
                    .append(',').append(f.totalACobrar() == null ? "" : f.totalACobrar()).append(',')
                    .append(f.tiquetes()).append('\n');
        }
        sb.append("TOTAL,,").append(report.cuotas()).append(',').append(report.comisiones()).append(',')
                .append(report.total()).append(',').append(report.tiquetes()).append('\n');
        return sb.toString();
    }

    /**
     * Texto seguro para CSV: entre comillas si trae coma, comillas o saltos de línea, y con un apóstrofo delante si
     * empieza como fórmula (=, +, -, @) para que una hoja de cálculo no la ejecute.
     */
    static String campo(String valor) {
        if (valor == null) {
            return "";
        }
        String v = valor;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
