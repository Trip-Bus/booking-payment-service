package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.web.dto.PlatformBillingResponse;
import com.tripbus.booking.web.dto.PlatformBillingRow;
import com.tripbus.booking.web.dto.SalesReportResponse;
import com.tripbus.booking.web.dto.SalesReportRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * US-190: ventas e ingresos con la comisión de TripBus. Todo se agrega en SQL (nunca se cargan las ventas en memoria).
 *
 * <p>Una venta es un pago con aprobado_en en el rango (fechas de Colombia). Por grupo: tiquetes vendidos (asientos de
 * ventas no reembolsadas; la diferencia de una reprogramación no es un tiquete nuevo), ingresos brutos (todas las
 * ventas), reembolsos (de esas ventas), ingresos netos, comisión de TripBus (0 si la venta se reembolsó) y neto para
 * la empresa. Montos en COP enteros.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    public static final int MAX_DIAS = 366;

    // Las consultas son constantes: la agrupación elige una de ellas y los valores van siempre como parámetros.
    private static final String DESDE_VENTAS = """
             FROM payments p
             JOIN bookings b ON b.id = p.booking_id
             LEFT JOIN refunds r ON r.payment_id = p.id
            WHERE p.aprobado_en >= :desde AND p.aprobado_en < :hasta
              AND (CAST(:companyId AS uuid) IS NULL OR b.company_id = CAST(:companyId AS uuid))""";

    private static final String METRICAS = """
            COALESCE(SUM(CASE WHEN r.id IS NULL AND b.origen_booking_id IS NULL
                              THEN cardinality(string_to_array(b.seat_numbers, ',')) ELSE 0 END), 0) AS tiquetes,
            COALESCE(ROUND(SUM(p.monto)), 0) AS brutos,
            COALESCE(ROUND(SUM(COALESCE(r.monto, 0))), 0) AS reembolsos,
            COALESCE(ROUND(SUM(CASE WHEN r.id IS NULL THEN COALESCE(p.comision_valor, 0) ELSE 0 END)), 0) AS comision,
            COUNT(*) FILTER (WHERE r.id IS NULL AND p.comision_porcentaje IS NULL) AS sin_comision,
            COALESCE(ROUND(SUM(b.descuento_cupon)), 0) AS descuentos""";

    private static final String GRUPO_DIA = "to_char(p.aprobado_en AT TIME ZONE 'America/Bogota', 'YYYY-MM-DD')";
    private static final String GRUPO_RUTA = "COALESCE(b.origen || ' - ' || b.destino, 'SIN_RUTA')";
    private static final String GRUPO_METODO = "p.metodo";
    private static final String GRUPO_CANAL = "b.canal";
    private static final String POR_GRUPO = " GROUP BY 1 ORDER BY 1";

    private static final String SQL_TOTALES = "SELECT " + METRICAS + DESDE_VENTAS;
    private static final String SQL_VIAJES_POR_RUTA =
            "SELECT DISTINCT " + GRUPO_RUTA + " AS grupo, b.itinerary_id" + DESDE_VENTAS;
    private static final String SQL_PLATAFORMA = """
            WITH empresas AS (
                SELECT company_id, MAX(empresa_nombre) AS nombre FROM bookings
                 WHERE company_id IS NOT NULL GROUP BY company_id
            ), ventas AS (
                SELECT b.company_id, """ + METRICAS + DESDE_VENTAS + """
                   AND b.company_id IS NOT NULL
                 GROUP BY b.company_id
            )
            SELECT e.company_id, e.nombre, COALESCE(v.tiquetes, 0) AS tiquetes, COALESCE(v.comision, 0) AS comision
              FROM empresas e LEFT JOIN ventas v ON v.company_id = e.company_id
             ORDER BY e.nombre NULLS LAST, e.company_id""";

    /** Agrupaciones del reporte de ventas, cada una con su consulta. */
    public enum Agrupacion {
        DIA("SELECT " + GRUPO_DIA + " AS grupo, " + METRICAS + DESDE_VENTAS + POR_GRUPO),
        RUTA("SELECT " + GRUPO_RUTA + " AS grupo, " + METRICAS + DESDE_VENTAS + POR_GRUPO),
        METODO("SELECT " + GRUPO_METODO + " AS grupo, " + METRICAS + DESDE_VENTAS + POR_GRUPO),
        CANAL("SELECT " + GRUPO_CANAL + " AS grupo, " + METRICAS + DESDE_VENTAS + POR_GRUPO);

        private final String consulta;

        Agrupacion(String consulta) {
            this.consulta = consulta;
        }

        public static Agrupacion of(String valor) {
            if (valor == null || valor.isBlank()) {
                return DIA;
            }
            try {
                return valueOf(valor.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_GROUPING", "agrupar debe ser dia, ruta, metodo o canal");
            }
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final SeatInventoryClient seatInventory;
    private final CatalogClient catalog;

    public ReportService(NamedParameterJdbcTemplate jdbc, SeatInventoryClient seatInventory, CatalogClient catalog) {
        this.jdbc = jdbc;
        this.seatInventory = seatInventory;
        this.catalog = catalog;
    }

    /**
     * Empresa del reporte según quien lo pide: ADMIN la que pida (null = todas); OPERADOR solo la suya (pedir otra
     * responde 403).
     */
    public static UUID empresaDelReporte(BookingViewer viewer, UUID companyId) {
        if (viewer.admin()) {
            return companyId;
        }
        if (!viewer.operador() || viewer.companyId() == null) {
            throw ApiException.forbidden("FORBIDDEN", "No tiene permisos para ver los reportes de ventas");
        }
        if (companyId != null && !companyId.equals(viewer.companyId())) {
            throw ApiException.forbidden("FORBIDDEN_COMPANY", "Solo puede ver los reportes de su empresa");
        }
        return viewer.companyId();
    }

    // ------------------------------------------------------------------ ventas

    public SalesReportResponse sales(LocalDate desde, LocalDate hasta, Agrupacion agrupar, UUID companyId) {
        if (hasta.isBefore(desde)) {
            throw ApiException.badRequest("INVALID_RANGE", "hasta no puede ser anterior a desde");
        }
        if (ChronoUnit.DAYS.between(desde, hasta) + 1 > MAX_DIAS) {
            throw ApiException.badRequest("RANGE_TOO_LONG", "El rango no puede superar " + MAX_DIAS + " días");
        }
        MapSqlParameterSource params = rango(desde, hasta.plusDays(1), companyId);

        List<Object[]> grupos = jdbc.query(agrupar.consulta, params,
                (rs, n) -> new Object[]{rs.getString("grupo"), metricas(rs)});
        long[] totales = jdbc.queryForObject(SQL_TOTALES, params, (rs, n) -> metricas(rs));

        Map<String, Double> ocupacion = agrupar == Agrupacion.RUTA ? ocupacionPorRuta(params) : Map.of();
        List<SalesReportRow> filas = new ArrayList<>();
        for (Object[] g : grupos) {
            String grupo = (String) g[0];
            filas.add(fila(grupo, (long[]) g[1], ocupacion.get(grupo)));
        }
        long[] t = Objects.requireNonNullElseGet(totales, () -> new long[6]);
        return new SalesReportResponse(desde, hasta, agrupar.name().toLowerCase(Locale.ROOT), companyId, filas,
                fila("TOTAL", t, null), t[4]);
    }

    private static MapSqlParameterSource rango(LocalDate desde, LocalDate hastaExclusivo, UUID companyId) {
        return new MapSqlParameterSource()
                .addValue("desde", inicioDelDia(desde))
                .addValue("hasta", inicioDelDia(hastaExclusivo))
                .addValue("companyId", companyId, Types.OTHER);
    }

    private static long[] metricas(ResultSet rs) throws SQLException {
        return new long[]{rs.getLong("tiquetes"), rs.getLong("brutos"), rs.getLong("reembolsos"),
                rs.getLong("comision"), rs.getLong("sin_comision"), rs.getLong("descuentos")};
    }

    private static SalesReportRow fila(String grupo, long[] m, Double ocupacion) {
        long netos = m[1] - m[2];
        return new SalesReportRow(grupo, m[0], m[1], m[5], m[2], netos, m[3], netos - m[3], ocupacion);
    }

    /**
     * Ocupación promedio (en %) de los viajes vendidos de cada ruta, con seat-inventory (bloques de 200 viajes). Si
     * seat-inventory no responde, todas quedan null y el reporte carga igual.
     */
    private Map<String, Double> ocupacionPorRuta(MapSqlParameterSource params) {
        Map<String, List<UUID>> viajesPorRuta = new LinkedHashMap<>();
        RowCallbackHandler porFila = rs -> viajesPorRuta
                .computeIfAbsent(rs.getString("grupo"), k -> new ArrayList<>())
                .add(rs.getObject("itinerary_id", UUID.class));
        jdbc.query(SQL_VIAJES_POR_RUTA, params, porFila);
        List<UUID> viajes = viajesPorRuta.values().stream().flatMap(List::stream).distinct().toList();
        Map<UUID, Double> porViaje = new HashMap<>();
        try {
            for (int i = 0; i < viajes.size(); i += SeatInventoryClient.MAX_OCCUPANCY_IDS) {
                List<UUID> bloque = viajes.subList(i, Math.min(i + SeatInventoryClient.MAX_OCCUPANCY_IDS, viajes.size()));
                for (SeatInventoryClient.Occupancy o : seatInventory.occupancy(bloque)) {
                    porViaje.put(o.itineraryId(), o.porcentajeOcupacion());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Seat Inventory no respondió la ocupación del reporte de ventas: {}", e.getMessage());
            return Map.of();
        }
        Map<String, Double> porRuta = new HashMap<>();
        viajesPorRuta.forEach((ruta, ids) -> ids.stream().filter(porViaje::containsKey)
                .mapToDouble(porViaje::get).average()
                .ifPresent(prom -> porRuta.put(ruta, BigDecimal.valueOf(prom).setScale(2, RoundingMode.HALF_UP)
                        .doubleValue())));
        return porRuta;
    }

    // ------------------------------------------------------------------ plataforma

    /** mes en formato YYYY-MM. */
    public static YearMonth mes(String valor) {
        try {
            return YearMonth.parse(valor == null ? "" : valor.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_MONTH", "mes debe tener el formato YYYY-MM");
        }
    }

    private record VentasEmpresa(UUID companyId, String nombre, long tiquetes, long comision) {
    }

    /**
     * Facturación de TripBus del mes por empresa: cuota mensual vigente el primer día del mes (catalog) + comisión de
     * las ventas del mes no reembolsadas. Incluye las empresas con reservas aunque no hayan vendido ese mes (pagan la
     * cuota igual).
     */
    public PlatformBillingResponse platform(YearMonth mes) {
        List<VentasEmpresa> ventas = jdbc.query(SQL_PLATAFORMA, rango(mes.atDay(1), mes.plusMonths(1).atDay(1), null),
                (rs, n) -> new VentasEmpresa(rs.getObject("company_id", UUID.class), rs.getString("nombre"),
                        rs.getLong("tiquetes"), rs.getLong("comision")));
        // Las cuotas se consultan a catalog ya con la conexión a la BD liberada.
        LocalDate vigencia = mes.atDay(1);
        List<PlatformBillingRow> filas = new ArrayList<>();
        long cuotas = 0;
        long comisiones = 0;
        long tiquetes = 0;
        boolean incompleto = false;
        for (VentasEmpresa v : ventas) {
            CatalogClient.CompanyTerms terms = catalog.companyTerms(v.companyId(), vigencia);
            Long cuota = terms != null && terms.cuotaMensual() != null
                    ? terms.cuotaMensual().setScale(0, RoundingMode.HALF_UP).longValue() : null;
            String nombre = terms != null && terms.nombre() != null ? terms.nombre() : v.nombre();
            filas.add(new PlatformBillingRow(v.companyId(), nombre, cuota, v.comision(),
                    cuota != null ? cuota + v.comision() : null, v.tiquetes()));
            comisiones += v.comision();
            tiquetes += v.tiquetes();
            if (cuota == null) {
                incompleto = true;
            } else {
                cuotas += cuota;
            }
        }
        return new PlatformBillingResponse(mes.toString(), filas, cuotas, comisiones, cuotas + comisiones, tiquetes,
                incompleto);
    }

    private static Timestamp inicioDelDia(LocalDate dia) {
        return Timestamp.from(dia.atStartOfDay(CommissionService.ZONA).toInstant());
    }
}
