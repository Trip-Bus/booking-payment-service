package com.tripbus.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.IdentityClient;
import com.tripbus.booking.client.RemoteErrors;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.Refund;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.RefundRepository;
import com.tripbus.booking.service.CommissionJob;
import com.tripbus.booking.service.CommissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-190: comisión de TripBus por venta y reportes de ventas e ingresos, contra PostgreSQL 16 y Redis 7 reales
 * (Testcontainers) con las migraciones Flyway reales. Catalog (condiciones comerciales) y Seat Inventory (ocupación)
 * se simulan con mocks. Cada prueba usa su propia empresa y sus propias fechas.
 */
@SpringBootTest(properties = {"tripbus.booking.trip-backfill-enabled=false",
        "tripbus.booking.commission-backfill-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class ReportesYComisionIT {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private RefundRepository refunds;
    @Autowired
    private CommissionService commissionService;
    @Autowired
    private CommissionJob commissionJob;

    @MockitoBean
    private SeatInventoryClient seatInventory;
    @MockitoBean
    private CatalogClient catalog;
    @MockitoBean
    private FlashOffersClient flashOffers;
    @MockitoBean
    private IdentityClient identity;
    @MockitoBean
    private PaymentGateway gateway;

    private UUID empresa;

    @BeforeEach
    void preparar() {
        reset(seatInventory, catalog);
        empresa = UUID.randomUUID();
    }

    // ------------------------------------------------------------------ datos

    private static Instant enBogota(String fechaHora) {
        return LocalDateTime.parse(fechaHora).atZone(BOGOTA).toInstant();
    }

    private record Venta(UUID bookingId, UUID paymentId, UUID itinerario) {
    }

    /** Inserta una reserva y su pago aprobado (una venta). comision null = aún sin registrar. */
    private Venta venta(UUID company, String origen, String destino, String canal, String metodo, int asientos,
                        long monto, Instant aprobadoEn, BigDecimal porcentaje, UUID itinerario, UUID origenBooking) {
        UUID bookingId = UUID.randomUUID();
        List<String> sillas = new ArrayList<>();
        for (int i = 1; i <= asientos; i++) {
            sillas.add(String.valueOf(i));
        }
        jdbc.update("""
                        INSERT INTO bookings (id, hold_id, user_id, itinerary_id, seat_numbers, estado, precio_unitario,
                            total, moneda, creado_en, actualizado_en, company_id, origen, destino, empresa_nombre, canal,
                            vendido_por, origen_booking_id)
                        VALUES (?, ?, ?, ?, ?, 'CONFIRMADA', ?, ?, 'COP', ?, ?, ?, ?, ?, 'Expreso Andino', ?, ?, ?)""",
                bookingId, UUID.randomUUID(), UUID.randomUUID(), itinerario, String.join(",", sillas),
                BigDecimal.valueOf(monto), BigDecimal.valueOf(monto), Timestamp.from(aprobadoEn),
                Timestamp.from(aprobadoEn), company, origen, destino, canal,
                "TAQUILLA".equals(canal) ? UUID.randomUUID() : null, origenBooking);
        UUID paymentId = UUID.randomUUID();
        BigDecimal valor = porcentaje == null ? null : Payment.comision(BigDecimal.valueOf(monto), porcentaje);
        jdbc.update("""
                        INSERT INTO payments (id, booking_id, monto, metodo, estado, idempotency_key, creado_en,
                            actualizado_en, aprobado_en, comision_porcentaje, comision_valor)
                        VALUES (?, ?, ?, ?, 'APROBADO', ?, ?, ?, ?, ?, ?)""",
                paymentId, bookingId, BigDecimal.valueOf(monto), metodo, "k-" + paymentId, Timestamp.from(aprobadoEn),
                Timestamp.from(aprobadoEn), Timestamp.from(aprobadoEn), porcentaje, valor);
        return new Venta(bookingId, paymentId, itinerario);
    }

    private Venta venta(UUID company, String origen, String destino, String canal, String metodo, int asientos,
                        long monto, Instant aprobadoEn, BigDecimal porcentaje) {
        return venta(company, origen, destino, canal, metodo, asientos, monto, aprobadoEn, porcentaje,
                UUID.randomUUID(), null);
    }

    /** Reembolsa la venta como lo hace el servicio: Refund.de revierte la comisión que tenga en ese momento. */
    private void reembolsar(UUID paymentId) {
        tx.executeWithoutResult(s -> {
            Payment p = payments.findById(paymentId).orElseThrow();
            refunds.save(Refund.de(p, p.getMonto(), "AFECTADA_POR_LA_EMPRESA", Instant.now()));
            p.reembolsar(Instant.now());
        });
    }

    private CatalogClient.CompanyTerms condiciones(String porcentaje, Long cuota) {
        return new CatalogClient.CompanyTerms(empresa, "Expreso Andino", "ACTIVA", new BigDecimal(porcentaje),
                cuota == null ? null : BigDecimal.valueOf(cuota));
    }

    private BigDecimal[] comisionDe(UUID paymentId) {
        return jdbc.queryForObject("select comision_porcentaje, comision_valor from payments where id = ?",
                (rs, n) -> new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)}, paymentId);
    }

    private RequestPostProcessor operador(UUID company) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "OPERADOR")
                        .claim("companyId", company.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor pasajero() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "PASAJERO"))
                .authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"));
    }

    private JsonNode reporte(String desde, String hasta, String agrupar, RequestPostProcessor quien) throws Exception {
        String body = mvc.perform(get("/reports/sales").param("desde", desde).param("hasta", hasta)
                        .param("agrupar", agrupar).with(quien))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static JsonNode grupo(JsonNode reporte, String nombre) {
        for (JsonNode g : reporte.get("grupos")) {
            if (g.get("grupo").asText().equals(nombre)) {
                return g;
            }
        }
        throw new AssertionError("No está el grupo " + nombre + " en " + reporte);
    }

    // ------------------------------------------------------------------ comisión

    @Test
    void laComisionSeGuardaConLaVigenciaDeLaFechaDeLaVentaEnColombia() {
        // 31 de enero a las 23:30 en Colombia (ya es 1 de febrero en UTC): rige la comisión del 31 de enero.
        Venta enero = venta(empresa, "Bogotá", "Cali", "APP", "TARJETA", 1, 95_555, enBogota("2026-01-31T23:30"), null);
        Venta febrero = venta(empresa, "Bogotá", "Cali", "APP", "TARJETA", 1, 95_555, enBogota("2026-02-01T08:00"), null);
        when(catalog.companyTerms(empresa, LocalDate.of(2026, 1, 31))).thenReturn(condiciones("8", 400_000L));
        when(catalog.companyTerms(empresa, LocalDate.of(2026, 2, 1))).thenReturn(condiciones("10", 400_000L));

        assertThat(commissionService.registrar(enero.paymentId())).isTrue();
        assertThat(commissionService.registrar(febrero.paymentId())).isTrue();

        // 95.555 × 8 % = 7.644,4 -> 7.644 ; 95.555 × 10 % = 9.555,5 -> 9.556 (al peso más cercano).
        assertThat(comisionDe(enero.paymentId())).containsExactly(new BigDecimal("8.00"), new BigDecimal("7644.00"));
        assertThat(comisionDe(febrero.paymentId())).containsExactly(new BigDecimal("10.00"), new BigDecimal("9556.00"));
        // Ya registrada: no se vuelve a consultar ni a cambiar.
        assertThat(commissionService.registrar(enero.paymentId())).isTrue();
        verify(catalog, times(1)).companyTerms(empresa, LocalDate.of(2026, 1, 31));
    }

    @Test
    void unCambioDeVigenciaNoAlteraLasVentasPasadas() {
        Venta pasada = venta(empresa, "Bogotá", "Tunja", "APP", "TARJETA", 1, 50_000, enBogota("2026-03-02T10:00"), null);
        when(catalog.companyTerms(eq(empresa), any())).thenReturn(condiciones("8", null));
        commissionService.registrar(pasada.paymentId());

        // La empresa negocia 15 % desde hoy: la venta pasada conserva su 8 % aunque el job vuelva a pasar.
        when(catalog.companyTerms(eq(empresa), any())).thenReturn(condiciones("15", null));
        Venta nueva = venta(empresa, "Bogotá", "Tunja", "APP", "TARJETA", 1, 50_000, enBogota("2026-03-20T10:00"), null);
        commissionJob.completar();

        assertThat(comisionDe(pasada.paymentId())).containsExactly(new BigDecimal("8.00"), new BigDecimal("4000.00"));
        assertThat(comisionDe(nueva.paymentId())).containsExactly(new BigDecimal("15.00"), new BigDecimal("7500.00"));
    }

    @Test
    void siCatalogNoRespondeLaVentaQuedaSinComisionYElJobLaCompleta() throws Exception {
        Venta venta = venta(empresa, "Cali", "Pasto", "APP", "NEQUI", 1, 80_000, enBogota("2026-04-05T09:00"), null);
        when(catalog.companyTerms(eq(empresa), any())).thenReturn(null);

        assertThat(commissionService.registrar(venta.paymentId())).isFalse();
        assertThat(comisionDe(venta.paymentId())).containsExactly(null, null);
        JsonNode antes = reporte("2026-04-05", "2026-04-05", "dia", operador(empresa));
        assertThat(antes.get("ventasSinComision").asLong()).isEqualTo(1);
        assertThat(antes.at("/totales/comisionTripBus").asLong()).isZero();

        when(catalog.companyTerms(empresa, LocalDate.of(2026, 4, 5))).thenReturn(condiciones("12.5", null));
        assertThat(commissionJob.completar()).isPositive();

        assertThat(comisionDe(venta.paymentId())).containsExactly(new BigDecimal("12.50"), new BigDecimal("10000.00"));
        JsonNode despues = reporte("2026-04-05", "2026-04-05", "dia", operador(empresa));
        assertThat(despues.get("ventasSinComision").asLong()).isZero();
        assertThat(despues.at("/totales/comisionTripBus").asLong()).isEqualTo(10_000);
    }

    @Test
    void laVentaSinEmpresaNoTieneComisionHastaQueLaTenga() {
        Venta sinEmpresa = venta(null, "A", "B", "APP", "TARJETA", 1, 10_000, enBogota("2026-04-06T09:00"), null);
        assertThat(commissionService.registrar(sinEmpresa.paymentId())).isFalse();
        assertThat(commissionService.registrar(UUID.randomUUID())).isFalse();
    }

    @Test
    void registrarDespuesDelCommitLoHaceEnSegundoPlano() {
        Venta venta = venta(empresa, "Cali", "Popayán", "TAQUILLA", "EFECTIVO", 1, 30_000,
                enBogota("2026-04-07T09:00"), null);
        when(catalog.companyTerms(eq(empresa), any())).thenReturn(condiciones("10", null));

        tx.executeWithoutResult(s -> commissionService.registrarDespuesDelCommit(venta.paymentId()));

        await().atMost(Duration.ofSeconds(10)).until(() -> comisionDe(venta.paymentId())[1] != null);
        assertThat(comisionDe(venta.paymentId())[1]).isEqualByComparingTo("3000");
    }

    @Test
    void elReembolsoRevierteLaComisionAunqueLlegueAntesDeRegistrarla() throws Exception {
        when(catalog.companyTerms(eq(empresa), any())).thenReturn(condiciones("10", null));
        // Reembolso de una venta que ya tenía comisión: la registra en negativo al instante.
        Venta conComision = venta(empresa, "Bogotá", "Neiva", "APP", "TARJETA", 1, 70_000,
                enBogota("2026-05-04T10:00"), new BigDecimal("10"));
        reembolsar(conComision.paymentId());
        // Reembolso antes de registrar la comisión: el job la registra y la revierte.
        Venta sinComision = venta(empresa, "Bogotá", "Neiva", "APP", "PSE", 2, 40_000,
                enBogota("2026-05-04T11:00"), null);
        reembolsar(sinComision.paymentId());
        commissionJob.completar();

        assertThat(jdbc.queryForObject("select comision_valor from refunds where payment_id = ?", BigDecimal.class,
                conComision.paymentId())).isEqualByComparingTo("-7000");
        assertThat(jdbc.queryForObject("select comision_valor from refunds where payment_id = ?", BigDecimal.class,
                sinComision.paymentId())).isEqualByComparingTo("-4000");
        JsonNode r = reporte("2026-05-04", "2026-05-04", "dia", operador(empresa));
        assertThat(r.at("/totales/ingresosBrutos").asLong()).isEqualTo(110_000);
        assertThat(r.at("/totales/reembolsos").asLong()).isEqualTo(110_000);
        assertThat(r.at("/totales/ingresosNetos").asLong()).isZero();
        assertThat(r.at("/totales/comisionTripBus").asLong()).isZero();
        assertThat(r.at("/totales/tiquetesVendidos").asLong()).isZero();
        assertThat(r.get("ventasSinComision").asLong()).isZero();
    }

    // ------------------------------------------------------------------ reportes

    /**
     * Día 1: APP/TARJETA Bogotá-Medellín 2 sillas 100.000 (10 % = 10.000) y TAQUILLA/EFECTIVO Bogotá-Cali 1 silla
     * 50.000 (5.000). Día 2: APP/PSE Bogotá-Medellín 1 silla 60.000 reembolsada (6.000 -> 0) y la diferencia de una
     * reprogramación APP/TARJETA Bogotá-Medellín 20.000 (2.000; no es un tiquete nuevo).
     */
    private List<Venta> ventasDeLaEmpresa() {
        BigDecimal diez = new BigDecimal("10");
        UUID medellin1 = UUID.randomUUID();
        UUID medellin2 = UUID.randomUUID();
        Venta a = venta(empresa, "Bogotá", "Medellín", "APP", "TARJETA", 2, 100_000, enBogota("2026-06-01T08:00"),
                diez, medellin1, null);
        Venta b = venta(empresa, "Bogotá", "Cali", "TAQUILLA", "EFECTIVO", 1, 50_000, enBogota("2026-06-01T09:00"),
                diez);
        Venta c = venta(empresa, "Bogotá", "Medellín", "APP", "PSE", 1, 60_000, enBogota("2026-06-02T10:00"), diez,
                medellin2, null);
        reembolsar(c.paymentId());
        Venta d = venta(empresa, "Bogotá", "Medellín", "APP", "TARJETA", 1, 20_000, enBogota("2026-06-02T23:59"), diez,
                medellin2, a.bookingId());
        // Otra empresa el mismo día: no aparece para el operador de esta.
        venta(UUID.randomUUID(), "Bogotá", "Medellín", "APP", "TARJETA", 3, 999_000, enBogota("2026-06-01T10:00"), diez);
        return List.of(a, b, c, d);
    }

    private static void totalesDeLaEmpresa(JsonNode r) {
        assertThat(r.at("/totales/grupo").asText()).isEqualTo("TOTAL");
        assertThat(r.at("/totales/tiquetesVendidos").asLong()).isEqualTo(3);
        assertThat(r.at("/totales/ingresosBrutos").asLong()).isEqualTo(230_000);
        assertThat(r.at("/totales/reembolsos").asLong()).isEqualTo(60_000);
        assertThat(r.at("/totales/ingresosNetos").asLong()).isEqualTo(170_000);
        assertThat(r.at("/totales/comisionTripBus").asLong()).isEqualTo(17_000);
        assertThat(r.at("/totales/netoEmpresa").asLong()).isEqualTo(153_000);
    }

    @Test
    void reportePorDiaConSusTotales() throws Exception {
        ventasDeLaEmpresa();

        JsonNode r = reporte("2026-06-01", "2026-06-02", "dia", operador(empresa));

        assertThat(r.get("agrupar").asText()).isEqualTo("dia");
        assertThat(r.get("companyId").asText()).isEqualTo(empresa.toString());
        assertThat(r.get("grupos")).hasSize(2);
        JsonNode dia1 = grupo(r, "2026-06-01");
        assertThat(dia1.get("tiquetesVendidos").asLong()).isEqualTo(3);
        assertThat(dia1.get("ingresosBrutos").asLong()).isEqualTo(150_000);
        assertThat(dia1.get("comisionTripBus").asLong()).isEqualTo(15_000);
        assertThat(dia1.get("netoEmpresa").asLong()).isEqualTo(135_000);
        assertThat(dia1.get("ocupacionPromedio").isNull()).isTrue();
        // 23:59 del 2 de junio en Colombia sigue siendo el 2 de junio (en UTC ya es el 3).
        JsonNode dia2 = grupo(r, "2026-06-02");
        assertThat(dia2.get("tiquetesVendidos").asLong()).isZero();
        assertThat(dia2.get("ingresosBrutos").asLong()).isEqualTo(80_000);
        assertThat(dia2.get("reembolsos").asLong()).isEqualTo(60_000);
        assertThat(dia2.get("ingresosNetos").asLong()).isEqualTo(20_000);
        assertThat(dia2.get("comisionTripBus").asLong()).isEqualTo(2_000);
        totalesDeLaEmpresa(r);
    }

    @Test
    void reportePorRutaConLaOcupacionPromedioDeSusViajes() throws Exception {
        List<Venta> ventas = ventasDeLaEmpresa();
        when(seatInventory.occupancy(anyList())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(0);
            List<SeatInventoryClient.Occupancy> res = new ArrayList<>();
            for (UUID id : ids) {
                if (id.equals(ventas.get(0).itinerario())) {
                    res.add(new SeatInventoryClient.Occupancy(id, 40, 30, 75.0));
                } else if (id.equals(ventas.get(2).itinerario())) {
                    res.add(new SeatInventoryClient.Occupancy(id, 40, 20, 50.0));
                } // el viaje a Cali es desconocido para seat-inventory: se omite
            }
            return res;
        });

        JsonNode r = reporte("2026-06-01", "2026-06-02", "ruta", operador(empresa));

        assertThat(r.get("grupos")).hasSize(2);
        JsonNode medellin = grupo(r, "Bogotá - Medellín");
        assertThat(medellin.get("tiquetesVendidos").asLong()).isEqualTo(2);
        assertThat(medellin.get("ingresosBrutos").asLong()).isEqualTo(180_000);
        assertThat(medellin.get("ocupacionPromedio").asDouble()).isEqualTo(62.5);
        assertThat(grupo(r, "Bogotá - Cali").get("ocupacionPromedio").isNull()).isTrue();
        totalesDeLaEmpresa(r);
    }

    @Test
    void siSeatInventoryNoRespondeLaOcupacionQuedaNullYElReporteCarga() throws Exception {
        ventasDeLaEmpresa();
        when(seatInventory.occupancy(anyList())).thenThrow(new RemoteErrors.RemoteUnavailableException("caído", null));

        JsonNode r = reporte("2026-06-01", "2026-06-02", "ruta", operador(empresa));

        assertThat(grupo(r, "Bogotá - Medellín").get("ocupacionPromedio").isNull()).isTrue();
        totalesDeLaEmpresa(r);
    }

    @Test
    void laOcupacionSePideEnBloquesDe200Viajes() throws Exception {
        for (int i = 0; i < 201; i++) {
            venta(empresa, "Ibagué", "Armenia", "APP", "TARJETA", 1, 1_000, enBogota("2026-06-10T10:00"), null);
        }
        when(seatInventory.occupancy(anyList())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(0);
            return ids.stream().map(id -> new SeatInventoryClient.Occupancy(id, 10, 1, 10.0)).toList();
        });

        JsonNode r = reporte("2026-06-10", "2026-06-10", "ruta", operador(empresa));

        verify(seatInventory, times(2)).occupancy(anyList());
        assertThat(grupo(r, "Ibagué - Armenia").get("ocupacionPromedio").asDouble()).isEqualTo(10.0);
        assertThat(r.at("/totales/tiquetesVendidos").asLong()).isEqualTo(201);
        assertThat(r.get("ventasSinComision").asLong()).isEqualTo(201);
    }

    @Test
    void reportePorMetodoYPorCanal() throws Exception {
        ventasDeLaEmpresa();

        JsonNode metodo = reporte("2026-06-01", "2026-06-02", "metodo", operador(empresa));
        assertThat(metodo.get("grupos")).hasSize(3);
        assertThat(grupo(metodo, "TARJETA").get("ingresosBrutos").asLong()).isEqualTo(120_000);
        assertThat(grupo(metodo, "EFECTIVO").get("comisionTripBus").asLong()).isEqualTo(5_000);
        assertThat(grupo(metodo, "PSE").get("ingresosNetos").asLong()).isZero();
        totalesDeLaEmpresa(metodo);

        JsonNode canal = reporte("2026-06-01", "2026-06-02", "canal", operador(empresa));
        assertThat(canal.get("grupos")).hasSize(2);
        assertThat(grupo(canal, "APP").get("ingresosBrutos").asLong()).isEqualTo(180_000);
        assertThat(grupo(canal, "TAQUILLA").get("tiquetesVendidos").asLong()).isEqualTo(1);
        totalesDeLaEmpresa(canal);
    }

    @Test
    void reporteEnCsv() throws Exception {
        ventasDeLaEmpresa();

        String csv = mvc.perform(get("/reports/sales.csv").param("desde", "2026-06-01").param("hasta", "2026-06-02")
                        .param("agrupar", "canal").with(operador(empresa)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"ventas_2026-06-01_2026-06-02.csv\""))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv.split("\n")).containsExactly(
                "grupo,tiquetes_vendidos,ingresos_brutos,descuento_cupones,reembolsos,ingresos_netos,comision_tripbus,"
                        + "neto_empresa",
                "APP,2,180000,0,60000,120000,12000,108000",
                "TAQUILLA,1,50000,0,0,50000,5000,45000",
                "TOTAL,3,230000,0,60000,170000,17000,153000");

        when(seatInventory.occupancy(anyList())).thenReturn(List.of());
        String porRuta = mvc.perform(get("/reports/sales.csv").param("desde", "2026-06-01").param("hasta", "2026-06-02")
                        .param("agrupar", "ruta").with(operador(empresa)))
                .andReturn().getResponse().getContentAsString();
        assertThat(porRuta).startsWith("grupo,tiquetes_vendidos,ingresos_brutos,descuento_cupones,reembolsos,ingresos_netos,"
                + "comision_tripbus,neto_empresa,ocupacion_promedio\n").contains("Bogotá - Cali,1,50000,0,0,50000,5000,45000,\n");
    }

    @Test
    void elOperadorSoloVeSuEmpresaYAdminVeTodasOLaQuePida() throws Exception {
        ventasDeLaEmpresa();
        UUID otra = UUID.randomUUID();

        mvc.perform(get("/reports/sales").param("desde", "2026-06-01").param("hasta", "2026-06-02")
                        .param("companyId", otra.toString()).with(operador(empresa)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_COMPANY"));
        mvc.perform(get("/reports/sales").param("desde", "2026-06-01").param("hasta", "2026-06-02")
                        .with(operador(otra)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.ingresosBrutos").value(0))
                .andExpect(jsonPath("$.grupos.length()").value(0));
        mvc.perform(get("/reports/sales").param("desde", "2026-06-01").param("hasta", "2026-06-02").with(pasajero()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/reports/sales").param("desde", "2026-06-01").param("hasta", "2026-06-02")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "OPERADOR"))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/reports/sales").param("desde", "2026-06-01").param("hasta", "2026-06-02")
                        .param("companyId", empresa.toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.ingresosBrutos").value(230_000));
        // Sin companyId el ADMIN ve todas: incluye la venta de la otra empresa de ese día.
        JsonNode todas = reporte("2026-06-01", "2026-06-02", "dia", admin());
        assertThat(todas.get("companyId").isNull()).isTrue();
        assertThat(todas.at("/totales/ingresosBrutos").asLong()).isGreaterThanOrEqualTo(230_000 + 999_000);
    }

    @Test
    void parametrosInvalidosResponden400() throws Exception {
        mvc.perform(get("/reports/sales").param("desde", "2026-01-01").param("hasta", "2027-01-02").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("RANGE_TOO_LONG"));
        mvc.perform(get("/reports/sales").param("desde", "2026-01-01").param("hasta", "2027-01-01").with(admin()))
                .andExpect(status().isOk());
        mvc.perform(get("/reports/sales").param("desde", "2026-02-01").param("hasta", "2026-01-01").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_RANGE"));
        mvc.perform(get("/reports/sales").param("desde", "2026-01-01").param("hasta", "2026-01-02")
                        .param("agrupar", "semana").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_GROUPING"));
        mvc.perform(get("/reports/sales").param("desde", "ayer").param("hasta", "2026-01-02").with(admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/reports/sales").param("hasta", "2026-01-02").with(admin()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ plataforma

    @Test
    void facturacionDeLaPlataformaDelMesSoloParaAdmin() throws Exception {
        BigDecimal ocho = new BigDecimal("8");
        venta(empresa, "Bogotá", "Medellín", "APP", "TARJETA", 2, 100_000, enBogota("2026-07-01T00:30"), ocho);
        Venta reembolsada = venta(empresa, "Bogotá", "Medellín", "APP", "TARJETA", 1, 50_000,
                enBogota("2026-07-15T10:00"), ocho);
        reembolsar(reembolsada.paymentId());
        // Otro mes: no cuenta.
        venta(empresa, "Bogotá", "Medellín", "APP", "TARJETA", 1, 90_000, enBogota("2026-06-30T23:00"), ocho);
        // Otra empresa sin ventas en julio: aparece igual (paga la cuota). Catalog responde sin nombre ni cuota: el
        // nombre sale de sus reservas y la cuota queda null (facturación incompleta).
        UUID otra = UUID.randomUUID();
        venta(otra, "Cali", "Pasto", "APP", "TARJETA", 1, 10_000, enBogota("2026-05-15T10:00"), ocho);
        when(catalog.companyTerms(empresa, LocalDate.of(2026, 7, 1))).thenReturn(condiciones("8", 450_000L));
        when(catalog.companyTerms(otra, LocalDate.of(2026, 7, 1))).thenReturn(
                new CatalogClient.CompanyTerms(otra, null, "ACTIVA", new BigDecimal("8"), null));

        String body = mvc.perform(get("/reports/platform").param("mes", "2026-07").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode r = objectMapper.readTree(body);

        assertThat(r.get("mes").asText()).isEqualTo("2026-07");
        JsonNode fila = null;
        JsonNode filaOtra = null;
        for (JsonNode e : r.get("empresas")) {
            if (e.get("companyId").asText().equals(empresa.toString())) {
                fila = e;
            } else if (e.get("companyId").asText().equals(otra.toString())) {
                filaOtra = e;
            }
        }
        assertThat(fila).isNotNull();
        assertThat(filaOtra).isNotNull();
        assertThat(filaOtra.get("empresa").asText()).isEqualTo("Expreso Andino");
        assertThat(filaOtra.get("cuotaMensual").isNull()).isTrue();
        assertThat(filaOtra.get("totalACobrar").isNull()).isTrue();
        assertThat(filaOtra.get("comision").asLong()).isZero();
        assertThat(fila.get("empresa").asText()).isEqualTo("Expreso Andino");
        assertThat(fila.get("cuotaMensual").asLong()).isEqualTo(450_000);
        assertThat(fila.get("comision").asLong()).isEqualTo(8_000);
        assertThat(fila.get("totalACobrar").asLong()).isEqualTo(458_000);
        assertThat(fila.get("tiquetes").asLong()).isEqualTo(2);
        // Las demás empresas de otras pruebas no vendieron en julio y catalog (mock) no da su cuota.
        assertThat(r.get("comisiones").asLong()).isEqualTo(8_000);
        assertThat(r.get("incompleto").asBoolean()).isTrue();
        assertThat(r.get("total").asLong()).isEqualTo(r.get("cuotas").asLong() + 8_000);

        String csv = mvc.perform(get("/reports/platform.csv").param("mes", "2026-07").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"plataforma_2026-07.csv\""))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("company_id,empresa,cuota_mensual,comision,total_a_cobrar,tiquetes\n")
                .contains(empresa + ",Expreso Andino,450000,8000,458000,2\n")
                .contains("\nTOTAL,,");

        mvc.perform(get("/reports/platform").param("mes", "2026-07").with(operador(empresa)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/reports/platform.csv").param("mes", "2026-07").with(operador(empresa)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/reports/platform").param("mes", "julio").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_MONTH"));
    }
}
