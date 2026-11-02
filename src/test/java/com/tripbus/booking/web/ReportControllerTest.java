package com.tripbus.booking.web;

import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.service.BookingViewer;
import com.tripbus.booking.service.ReportService;
import com.tripbus.booking.web.dto.PlatformBillingResponse;
import com.tripbus.booking.web.dto.PlatformBillingRow;
import com.tripbus.booking.web.dto.SalesReportResponse;
import com.tripbus.booking.web.dto.SalesReportRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US-190: CSV de los reportes, parámetros y alcance por rol (sin BD). */
class ReportControllerTest {

    @Test
    void losCamposDelCsvSeEscapanYNoSeEjecutanComoFormula() {
        assertThat(ReportController.campo(null)).isEmpty();
        assertThat(ReportController.campo("")).isEmpty();
        assertThat(ReportController.campo("Bogotá - Cali")).isEqualTo("Bogotá - Cali");
        assertThat(ReportController.campo("Cali, Valle")).isEqualTo("\"Cali, Valle\"");
        assertThat(ReportController.campo("Expreso \"El Rápido\"")).isEqualTo("\"Expreso \"\"El Rápido\"\"\"");
        assertThat(ReportController.campo("línea\nnueva")).isEqualTo("\"línea\nnueva\"");
        assertThat(ReportController.campo("a\rb")).isEqualTo("\"a\rb\"");
        assertThat(ReportController.campo("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ReportController.campo("+57")).isEqualTo("'+57");
        assertThat(ReportController.campo("-1")).isEqualTo("'-1");
        assertThat(ReportController.campo("@a")).isEqualTo("'@a");
    }

    @Test
    void csvDeVentasConOcupacionConDosDecimalesOVacia() {
        SalesReportResponse r = new SalesReportResponse(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), "ruta",
                null, List.of(new SalesReportRow("A - B", 1, 10, 0, 10, 1, 9, 62.5),
                new SalesReportRow("C - D", 2, 20, 5, 15, 2, 13, null)),
                new SalesReportRow("TOTAL", 3, 30, 5, 25, 3, 22, null), 0);

        assertThat(ReportController.salesCsv(r).split("\n")).containsExactly(
                "grupo,tiquetes_vendidos,ingresos_brutos,reembolsos,ingresos_netos,comision_tripbus,neto_empresa,"
                        + "ocupacion_promedio",
                "A - B,1,10,0,10,1,9,62.50",
                "C - D,2,20,5,15,2,13,",
                "TOTAL,3,30,5,25,3,22,");
    }

    @Test
    void csvDeLaPlataformaConCuotaDesconocida() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        PlatformBillingResponse r = new PlatformBillingResponse("2026-07", List.of(
                new PlatformBillingRow(a, "Expreso, S.A.", 450_000L, 8_000, 458_000L, 2),
                new PlatformBillingRow(b, null, null, 0, null, 0)), 450_000, 8_000, 458_000, 2, true);

        assertThat(ReportController.platformCsv(r).split("\n")).containsExactly(
                "company_id,empresa,cuota_mensual,comision,total_a_cobrar,tiquetes",
                a + ",\"Expreso, S.A.\",450000,8000,458000,2",
                b + ",,,0,,0",
                "TOTAL,,450000,8000,458000,2");
    }

    @Test
    void agrupacionYMesSeValidan() {
        assertThat(ReportService.Agrupacion.of(null)).isEqualTo(ReportService.Agrupacion.DIA);
        assertThat(ReportService.Agrupacion.of(" ")).isEqualTo(ReportService.Agrupacion.DIA);
        assertThat(ReportService.Agrupacion.of(" Ruta ")).isEqualTo(ReportService.Agrupacion.RUTA);
        assertThatThrownBy(() -> ReportService.Agrupacion.of("hora")).isInstanceOf(ApiException.class);
        assertThat(ReportService.mes(" 2026-07 ")).isEqualTo(YearMonth.of(2026, 7));
        assertThatThrownBy(() -> ReportService.mes(null)).isInstanceOf(ApiException.class)
                .hasMessageContaining("YYYY-MM");
        assertThatThrownBy(() -> ReportService.mes("2026-13")).isInstanceOf(ApiException.class);
    }

    @Test
    void empresaDelReporteSegunElRol() {
        UUID empresa = UUID.randomUUID();
        UUID otra = UUID.randomUUID();
        BookingViewer admin = new BookingViewer(UUID.randomUUID(), true, false, null);
        BookingViewer operador = new BookingViewer(UUID.randomUUID(), false, true, empresa);

        assertThat(ReportService.empresaDelReporte(admin, null)).isNull();
        assertThat(ReportService.empresaDelReporte(admin, otra)).isEqualTo(otra);
        assertThat(ReportService.empresaDelReporte(operador, null)).isEqualTo(empresa);
        assertThat(ReportService.empresaDelReporte(operador, empresa)).isEqualTo(empresa);
        assertThatThrownBy(() -> ReportService.empresaDelReporte(operador, otra))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("FORBIDDEN_COMPANY");
                });
        assertThatThrownBy(() -> ReportService.empresaDelReporte(
                new BookingViewer(UUID.randomUUID(), false, true, null), null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ReportService.empresaDelReporte(
                new BookingViewer(UUID.randomUUID(), false, false, empresa), empresa)).isInstanceOf(ApiException.class);
    }
}
