package com.tripbus.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.IdentityClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.payment.WebhookSigner;
import com.tripbus.booking.service.BookingService;
import com.tripbus.booking.service.CommissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 6: precio desde catalog por tipo de pasajero y categoría de asiento (US-138/US-141), compra de un tramo
 * (US-142), comprobantes de pago y de reembolso (US-146) y cupones (US-147), contra PostgreSQL 16 y Redis 7 reales
 * (Testcontainers) con las migraciones Flyway reales. Seat Inventory, Catalog, Flash Offers, Identity y la pasarela se
 * simulan con mocks. Cada prueba usa su propio viaje, su propia empresa y su propio usuario.
 */
@SpringBootTest(properties = {"tripbus.booking.trip-backfill-enabled=false",
        "tripbus.booking.commission-backfill-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class TarifasCuponesYComprobantesIT {

    private static final Pattern NUMERO_COMPROBANTE = Pattern.compile("Comprobante N\\. (TB-\\d{8})");

    /** Paradas de la ruta Bogotá - Ibagué, por orden. */
    private static final Map<Integer, String> PARADAS = Map.of(1, "Bogotá", 2, "Fusagasugá", 3, "Girardot", 4, "Ibagué");
    private static final BigDecimal BASE = new BigDecimal("100000");

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
    private WebhookSigner signer;
    @Autowired
    private BookingService bookingService;

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
    private UUID ruta;
    private UUID viaje;
    private UUID usuario;

    @BeforeEach
    void preparar() {
        reset(seatInventory, catalog, flashOffers, gateway);
        empresa = UUID.randomUUID();
        ruta = UUID.randomUUID();
        viaje = UUID.randomUUID();
        usuario = UUID.randomUUID();
        when(catalog.itinerary(any())).thenAnswer(inv -> new CatalogClient.ItinerarySummary(inv.getArgument(0),
                "Bogotá", "Ibagué", Instant.now().plus(Duration.ofDays(2)), Instant.now().plus(Duration.ofDays(2)),
                "Expreso Andino", "ABC123", empresa, "PROGRAMADO", ruta));
        // Como catalog: 100.000 por pasajero; NINO -30.000, ADULTO_MAYOR -20.000; el asiento 1 es VIP (+15.000).
        when(catalog.pricing(any())).thenAnswer(inv -> {
            CatalogClient.PricingRequest r = inv.getArgument(0);
            List<CatalogClient.PassengerPrice> precios = new ArrayList<>();
            for (CatalogClient.PricingPassenger p : r.pasajeros()) {
                BigDecimal descuento = switch (p.tipoPasajero()) {
                    case "NINO" -> new BigDecimal("30000");
                    case "ADULTO_MAYOR" -> new BigDecimal("20000");
                    default -> BigDecimal.ZERO;
                };
                boolean vip = "1".equals(p.asiento());
                BigDecimal recargo = vip ? new BigDecimal("15000") : BigDecimal.ZERO;
                precios.add(new CatalogClient.PassengerPrice(p.asiento(), p.tipoPasajero(), vip ? "VIP" : "ESTANDAR",
                        BASE, descuento, recargo, BASE.subtract(descuento).add(recargo)));
            }
            int desde = r.fromOrden() != null ? r.fromOrden() : 1;
            int hasta = r.toOrden() != null ? r.toOrden() : 4;
            return new CatalogClient.PricingResult(r.itineraryId(), "PROGRAMADO", "COP",
                    new CatalogClient.Tramo(desde, PARADAS.get(desde), hasta, PARADAS.get(hasta)), precios);
        });
        when(catalog.companyTerms(any(), any())).thenAnswer(inv -> new CatalogClient.CompanyTerms(inv.getArgument(0),
                "Expreso Andino S.A.S.", "ACTIVA", new BigDecimal("10"), new BigDecimal("500000"), "900123456-7"));
        when(gateway.charge(any(), any(), anyString(), anyString())).thenAnswer(inv ->
                "sbx_" + UUID.randomUUID().toString().substring(0, 12));
        when(gateway.charge(any(), any(), anyString(), any(PaymentGateway.ChargeRequest.class))).thenAnswer(inv ->
                new PaymentGateway.ChargeResult("sbx_" + UUID.randomUUID().toString().substring(0, 12), null));
    }

    // ------------------------------------------------------------------ ayudas

    private RequestPostProcessor pasajero() {
        return pasajero(usuario);
    }

    private static RequestPostProcessor pasajero(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("role", "PASAJERO"))
                .authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"));
    }

    private static RequestPostProcessor operador(UUID company) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "OPERADOR")
                .claim("companyId", company.toString())).authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private UUID retencion(UUID offerId, Integer desde, Integer hasta, String... asientos) {
        return retencion(usuario, viaje, offerId, desde, hasta, asientos);
    }

    private UUID retencion(UUID userId, UUID itinerario, UUID offerId, Integer desde, Integer hasta, String... asientos) {
        UUID holdId = UUID.randomUUID();
        when(seatInventory.getHold(holdId)).thenReturn(new SeatInventoryClient.Hold(holdId, itinerario,
                List.of(asientos), userId, offerId, null, "ACTIVA", Instant.now().plusSeconds(600), 600, desde, hasta));
        return holdId;
    }

    private String cuerpo(UUID holdId, String cupon, String... tipos) {
        StringBuilder pasajeros = new StringBuilder();
        for (int i = 0; i < tipos.length; i++) {
            pasajeros.append(i > 0 ? "," : "").append("""
                    {"nombre":"Pasajero %d","tipoDocumento":"CC","numeroDocumento":"10203040%d","tipoPasajero":%s}"""
                    .formatted(i, i, tipos[i] == null ? "null" : "\"" + tipos[i] + "\""));
        }
        return """
                {"holdId":"%s","pasajeros":[%s],"cupon":%s}""".formatted(holdId, pasajeros,
                cupon == null ? "null" : "\"" + cupon + "\"");
    }

    private MvcResult crear(UUID holdId, String cupon, RequestPostProcessor quien, String... tipos) throws Exception {
        return mvc.perform(post("/bookings").with(quien).contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo(holdId, cupon, tipos))).andReturn();
    }

    private JsonNode creada(UUID holdId, String cupon, String... tipos) throws Exception {
        MvcResult r = crear(holdId, cupon, pasajero(), tipos);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private static String codigoDeError(MvcResult r) throws IOException {
        return new ObjectMapper().readTree(r.getResponse().getContentAsString()).at("/error/code").asText();
    }

    private String pagar(UUID bookingId, String token) throws Exception {
        String r = mvc.perform(post("/bookings/{id}/pay", bookingId).with(pasajero())
                        .header("Idempotency-Key", "pago-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardToken\":\"" + token + "\",\"cardLast4\":\"4242\",\"cardBrand\":\"VISA\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(r).get("paymentId").asText();
    }

    private void webhook(String paymentId, String estado, BigDecimal monto) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("eventId", "evt_" + UUID.randomUUID(),
                "reference", paymentId, "status", estado, "amount", monto, "currency", "COP",
                "timestamp", Instant.now().toString()));
        mvc.perform(post("/webhooks/payment").contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature", signer.sign(body)).content(body)).andExpect(status().isOk());
    }

    /** Crea, paga y confirma una reserva (el webhook aprueba y seat-inventory confirma el asiento). */
    private JsonNode comprada(UUID holdId, String cupon, String... tipos) throws Exception {
        JsonNode reserva = creada(holdId, cupon, tipos);
        UUID bookingId = UUID.fromString(reserva.get("bookingId").asText());
        when(seatInventory.confirmSale(eq(holdId), eq(bookingId))).thenReturn(null);
        String paymentId = pagar(bookingId, "tok_visa_approved");
        webhook(paymentId, "APPROVED", reserva.get("total").decimalValue());
        String detalle = mvc.perform(get("/bookings/{id}", bookingId).with(pasajero()))
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(detalle);
    }

    private static String numeroComprobante(String texto) {
        Matcher m = NUMERO_COMPROBANTE.matcher(texto);
        assertThat(m.find()).as("número de comprobante en el PDF").isTrue();
        return m.group(1);
    }

    private static String texto(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        StringBuilder sb = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            sb.append(extractor.getTextFromPage(i)).append('\n');
        }
        return sb.toString();
    }

    private String cupon(String codigo, RequestPostProcessor quien, UUID companyId, String extra) throws Exception {
        String body = """
                {"codigo":"%s","tipo":"PORCENTAJE","valor":20,"companyId":%s,"vigenteDesde":"%s","vigenteHasta":"%s"%s}"""
                .formatted(codigo, companyId == null ? "null" : "\"" + companyId + "\"",
                        Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(30)),
                        extra == null ? "" : "," + extra);
        String r = mvc.perform(post("/coupons").with(quien).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(r).get("couponId").asText();
    }

    private int usos(String couponId) {
        return jdbc.queryForObject("select usos from coupons where id = ?", Integer.class, UUID.fromString(couponId));
    }

    private static String codigo() {
        return "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    // ------------------------------------------------------------------ US-138 / US-141

    @Test
    void elTotalEsLaSumaDelPrecioDeCatalogPorTipoDePasajeroYCategoria() throws Exception {
        UUID holdId = retencion(null, null, null, "1", "2", "3");

        JsonNode r = creada(holdId, null, null, "nino", "ADULTO_MAYOR");

        // VIP adulto 115.000 + niño 70.000 + adulto mayor 80.000.
        assertThat(r.get("subtotal").decimalValue()).isEqualByComparingTo("265000");
        assertThat(r.get("total").decimalValue()).isEqualByComparingTo("265000");
        assertThat(r.get("descuentoCupon").decimalValue()).isZero();
        assertThat(r.get("tramo").isNull()).isTrue();
        JsonNode p = r.get("pasajeros");
        assertThat(p).hasSize(3);
        assertThat(p).anySatisfy(x -> {
            assertThat(x.get("asiento").asText()).isEqualTo("1");
            assertThat(x.get("tipoPasajero").asText()).isEqualTo("ADULTO");
            assertThat(x.get("categoriaAsiento").asText()).isEqualTo("VIP");
            assertThat(x.get("precio").decimalValue()).isEqualByComparingTo("115000");
        });
        assertThat(p).anySatisfy(x -> assertThat(x.get("tipoPasajero").asText()).isEqualTo("NINO"));
        assertThat(jdbc.queryForObject("select sum(precio) from passengers where booking_id = ?", BigDecimal.class,
                UUID.fromString(r.get("bookingId").asText()))).isEqualByComparingTo("265000");
    }

    @Test
    void unTipoDePasajeroDesconocidoEsInvalido() throws Exception {
        MvcResult r = crear(retencion(null, null, null, "4"), null, pasajero(), "BEBE");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void siCatalogNoRespondeNoSeCreaLaReserva() throws Exception {
        org.mockito.Mockito.doThrow(ApiException.unavailable("PRICING_UNAVAILABLE", "catalog caído"))
                .when(catalog).pricing(any());
        UUID holdId = retencion(null, null, null, "5");

        MvcResult r = crear(holdId, null, pasajero(), "ADULTO");

        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(codigoDeError(r)).isEqualTo("PRICING_UNAVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from bookings where hold_id = ?", Long.class, holdId)).isZero();
    }

    // ------------------------------------------------------------------ US-142

    @Test
    void elTramoQuedaEnLaReservaElTiqueteElPdfYElManifiesto() throws Exception {
        JsonNode completa = comprada(retencion(null, null, null, "7"), null, "ADULTO");
        JsonNode tramo = comprada(retencion(null, 2, 4, "8"), null, "ADULTO");

        assertThat(tramo.at("/tramo/paradaAbordaje").asText()).isEqualTo("Fusagasugá");
        assertThat(tramo.at("/tramo/paradaBajada").asText()).isEqualTo("Ibagué");
        assertThat(tramo.at("/tramo/fromOrden").asInt()).isEqualTo(2);
        String ticketId = tramo.get("ticketId").asText();
        mvc.perform(get("/tickets/{id}", ticketId).with(pasajero()))
                .andExpect(jsonPath("$.tramo.paradaAbordaje").value("Fusagasugá"))
                .andExpect(jsonPath("$.tramo.paradaBajada").value("Ibagué"));
        assertThat(jdbc.queryForMap("select parada_abordaje, parada_bajada from tickets where id = ?",
                UUID.fromString(ticketId))).containsEntry("parada_abordaje", "Fusagasugá");
        byte[] pdf = mvc.perform(get("/tickets/{id}/pdf", ticketId).with(pasajero()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(texto(pdf)).contains("Sube en").contains("Fusagasugá").contains("Baja en").contains("Ibagué");

        // Manifiesto: agrupado por parada de abordaje (primero el origen) y con la parada donde baja cada uno.
        String manifiesto = mvc.perform(get("/itineraries/{id}/manifest", viaje).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode grupos = objectMapper.readTree(manifiesto).get("porParada");
        assertThat(grupos).hasSize(2);
        assertThat(grupos.get(0).get("parada").asText()).isEqualTo("Bogotá");
        assertThat(grupos.get(0).at("/pasajeros/0/paradaBajada").asText()).isEqualTo("Ibagué");
        assertThat(grupos.get(1).get("parada").asText()).isEqualTo("Fusagasugá");
        assertThat(grupos.get(1).get("orden").asInt()).isEqualTo(2);
        assertThat(grupos.get(1).at("/pasajeros/0/bookingId").asText()).isEqualTo(tramo.get("bookingId").asText());
        assertThat(completa.get("tramo").isNull()).isTrue();
        mvc.perform(get("/itineraries/{id}/manifest", viaje).param("format", "csv").with(admin()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(",Fusagasugá,Ibagué\n")));
    }

    // ------------------------------------------------------------------ US-146

    @Test
    void comprobantesDePagoYDeReembolsoConNumeroConsecutivo() throws Exception {
        String couponId = cupon(codigo(), admin(), null, null);
        String codigo = jdbc.queryForObject("select codigo from coupons where id = ?", String.class,
                UUID.fromString(couponId));
        JsonNode reserva = comprada(retencion(null, null, null, "1", "2"), codigo.toLowerCase(), "ADULTO", "NINO");
        String paymentId = reserva.at("/pago/paymentId").asText();

        byte[] pdf = mvc.perform(get("/payments/{id}/receipt.pdf", paymentId).with(pasajero()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        String texto = texto(pdf);
        // 115.000 + 70.000 = 185.000; cupón de 20 % = 37.000; pagado 148.000.
        assertThat(texto).contains("Comprobante de pago").contains("Comprobante N. TB-")
                .contains("Expreso Andino S.A.S.").contains("900123456-7").contains("TARJETA VISA **** 4242")
                .contains("Pasajero 0").contains("NINO").contains("VIP").contains("Cupón " + codigo)
                .contains("37000 COP").contains("Total pagado").contains("148000 COP")
                .doesNotContainIgnoringCase("comisi");
        String numero = numeroComprobante(texto);
        // El número no cambia si se vuelve a pedir.
        assertThat(texto(mvc.perform(get("/payments/{id}/receipt.pdf", paymentId).with(admin()))
                .andReturn().getResponse().getContentAsByteArray())).contains(numero);
        mvc.perform(get("/payments/{id}/receipt.pdf", paymentId).with(operador(empresa))).andExpect(status().isOk());
        mvc.perform(get("/payments/{id}/receipt.pdf", paymentId).with(operador(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/payments/{id}/receipt.pdf", paymentId).with(pasajero(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/payments/{id}/receipt.pdf", UUID.randomUUID()).with(admin())).andExpect(status().isNotFound());

        // La comisión de TripBus se calcula sobre lo pagado (148.000 × 10 %).
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "select comision_valor from payments where id = ?", BigDecimal.class, UUID.fromString(paymentId)) != null);
        assertThat(jdbc.queryForObject("select comision_valor from payments where id = ?", BigDecimal.class,
                UUID.fromString(paymentId))).isEqualByComparingTo("14800");
        // Los reportes de ventas suman el descuento por cupones.
        String hoy = LocalDate.now(CommissionService.ZONA).toString();
        mvc.perform(get("/reports/sales").param("desde", hoy).param("hasta", hoy).with(operador(empresa)))
                .andExpect(jsonPath("$.totales.descuentoCupones").value(37000))
                .andExpect(jsonPath("$.totales.ingresosBrutos").value(148000));

        // Reembolso: el asiento ya no se pudo confirmar.
        UUID holdId = retencion(null, null, null, "3");
        JsonNode otra = creada(holdId, null, "ADULTO");
        UUID otraId = UUID.fromString(otra.get("bookingId").asText());
        when(seatInventory.confirmSale(holdId, otraId))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "expirada"));
        webhook(pagar(otraId, "tok_visa_approved"), "APPROVED", otra.get("total").decimalValue());
        UUID refundId = jdbc.queryForObject("select r.id from refunds r join payments p on p.id = r.payment_id"
                + " where p.booking_id = ?", UUID.class, otraId);
        String reembolso = texto(mvc.perform(get("/refunds/{id}/receipt.pdf", refundId).with(pasajero()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(reembolso).contains("Comprobante de reembolso").contains("Total reembolsado").contains("100000 COP")
                .contains("ASIENTO_NO_CONFIRMADO");
        String numeroReembolso = numeroComprobante(reembolso);
        assertThat(numeroReembolso.compareTo(numero)).isPositive();
        mvc.perform(get("/refunds/{id}/receipt.pdf", refundId).with(pasajero(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/refunds/{id}/receipt.pdf", UUID.randomUUID()).with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void unPagoSinAprobarNoTieneComprobante() throws Exception {
        JsonNode reserva = creada(retencion(null, null, null, "9"), null, "ADULTO");
        String paymentId = pagar(UUID.fromString(reserva.get("bookingId").asText()), "tok_visa_approved");

        mvc.perform(get("/payments/{id}/receipt.pdf", paymentId).with(pasajero()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_NOT_AVAILABLE"));
    }

    // ------------------------------------------------------------------ US-147

    @Test
    void cuponesVencidoDeOtraEmpresaDeOtraRutaConCompraMinimaYConOferta() throws Exception {
        String vencido = codigo();
        mvc.perform(post("/coupons").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","tipo":"VALOR","valor":5000,"vigenteDesde":"%s","vigenteHasta":"%s"}"""
                        .formatted(vencido, Instant.now().minus(Duration.ofDays(10)), Instant.now().minus(Duration.ofDays(1)))))
                .andExpect(status().isCreated());
        String futuro = codigo();
        mvc.perform(post("/coupons").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","tipo":"VALOR","valor":5000,"vigenteDesde":"%s","vigenteHasta":"%s"}"""
                        .formatted(futuro, Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(3)))))
                .andExpect(status().isCreated());
        String otraEmpresa = codigo();
        cupon(otraEmpresa, admin(), UUID.randomUUID(), null);
        String otraRuta = codigo();
        cupon(otraRuta, admin(), null, "\"routeIds\":[\"" + UUID.randomUUID() + "\"]");
        String deEstaRuta = codigo();
        cupon(deEstaRuta, admin(), empresa, "\"routeIds\":[\"" + ruta + "\"]");
        String minimo = codigo();
        cupon(minimo, admin(), null, "\"compraMinima\":500000");

        assertThat(codigoDeError(crear(retencion(null, null, null, "2"), vencido, pasajero(), "ADULTO")))
                .isEqualTo("COUPON_EXPIRED");
        assertThat(codigoDeError(crear(retencion(null, null, null, "2"), futuro, pasajero(), "ADULTO")))
                .isEqualTo("COUPON_NOT_YET_VALID");
        assertThat(codigoDeError(crear(retencion(null, null, null, "2"), otraEmpresa, pasajero(), "ADULTO")))
                .isEqualTo("COUPON_NOT_APPLICABLE");
        assertThat(codigoDeError(crear(retencion(null, null, null, "2"), otraRuta, pasajero(), "ADULTO")))
                .isEqualTo("COUPON_NOT_APPLICABLE");
        assertThat(codigoDeError(crear(retencion(null, null, null, "2"), minimo, pasajero(), "ADULTO")))
                .isEqualTo("COUPON_MINIMUM_NOT_MET");
        assertThat(codigoDeError(crear(retencion(null, null, null, "2"), "NO-EXISTE", pasajero(), "ADULTO")))
                .isEqualTo("COUPON_NOT_FOUND");
        // De oferta: no se combina.
        UUID oferta = UUID.randomUUID();
        UUID holdOferta = retencion(oferta, null, null, "2");
        when(flashOffers.reservationByHold(oferta, holdOferta)).thenReturn(new FlashOffersClient.OfferReservation(
                UUID.randomUUID(), oferta, holdOferta, usuario, "2", new BigDecimal("49900"), "ACTIVA"));
        MvcResult conOferta = crear(holdOferta, deEstaRuta, pasajero(), "ADULTO");
        assertThat(conOferta.getResponse().getStatus()).isEqualTo(409);
        assertThat(codigoDeError(conOferta)).isEqualTo("COUPON_NOT_COMBINABLE");
        // El de la empresa y la ruta del viaje sí aplica: 20 % de 100.000.
        JsonNode valida = creada(retencion(null, null, null, "2"), deEstaRuta, "ADULTO");
        assertThat(valida.get("descuentoCupon").decimalValue()).isEqualByComparingTo("20000");
        assertThat(valida.get("total").decimalValue()).isEqualByComparingTo("80000");
        assertThat(valida.get("cuponCodigo").asText()).isEqualTo(deEstaRuta);
    }

    @Test
    void cuponAgotadoYLimitePorUsuario() throws Exception {
        String unSoloUso = codigo();
        String couponId = cupon(unSoloUso, admin(), null, "\"usosMaximos\":1");
        creada(retencion(null, null, null, "2"), unSoloUso, "ADULTO");
        assertThat(usos(couponId)).isEqualTo(1);
        assertThat(codigoDeError(crear(retencion(null, null, null, "3"), unSoloUso, pasajero(), "ADULTO")))
                .isEqualTo("COUPON_EXHAUSTED");

        String porUsuario = codigo();
        cupon(porUsuario, admin(), null, "\"usosPorUsuario\":1");
        creada(retencion(null, null, null, "2"), porUsuario, "ADULTO");
        assertThat(codigoDeError(crear(retencion(null, null, null, "3"), porUsuario, pasajero(), "ADULTO")))
                .isEqualTo("COUPON_USER_LIMIT_REACHED");
        UUID otro = UUID.randomUUID();
        assertThat(crear(retencion(otro, viaje, null, null, null, "4"), porUsuario, pasajero(otro), "ADULTO")
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void elUsoSeLiberaSiLaReservaVenceOElPagoSeRechaza() throws Exception {
        String codigo = codigo();
        String couponId = cupon(codigo, admin(), null, "\"usosMaximos\":1");
        UUID holdId = retencion(null, null, null, "2");
        JsonNode reserva = creada(holdId, codigo, "ADULTO");
        assertThat(usos(couponId)).isEqualTo(1);

        bookingService.onHoldExpired(holdId);

        assertThat(usos(couponId)).isZero();
        assertThat(jdbc.queryForObject("select estado from coupon_redemptions where booking_id = ?", String.class,
                UUID.fromString(reserva.get("bookingId").asText()))).isEqualTo("LIBERADO");
        bookingService.onHoldExpired(holdId); // idempotente
        assertThat(usos(couponId)).isZero();

        // Se puede volver a usar; si el pago se rechaza el uso vuelve a liberarse.
        JsonNode otra = creada(retencion(null, null, null, "3"), codigo, "ADULTO");
        assertThat(usos(couponId)).isEqualTo(1);
        UUID otraId = UUID.fromString(otra.get("bookingId").asText());
        webhook(pagar(otraId, "tok_visa_declined"), "DECLINED", otra.get("total").decimalValue());
        mvc.perform(get("/bookings/{id}", otraId).with(pasajero())).andExpect(jsonPath("$.estado").value("PAGO_RECHAZADO"));
        assertThat(usos(couponId)).isZero();
    }

    @Test
    void cincuentaComprasSimultaneasConUnCuponDeDiezUsosSoloDiezLoLogran() throws Exception {
        String codigo = codigo();
        String couponId = cupon(codigo, admin(), null, "\"usosMaximos\":10");
        List<UUID> holds = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            holds.add(retencion(null, null, null, String.valueOf(10 + i)));
        }

        ExecutorService hilos = Executors.newFixedThreadPool(50);
        List<Future<MvcResult>> compras = new ArrayList<>();
        for (UUID holdId : holds) {
            compras.add(hilos.submit(() -> crear(holdId, codigo, pasajero(), "ADULTO")));
        }
        int creadas = 0;
        int agotadas = 0;
        for (Future<MvcResult> f : compras) {
            MvcResult r = f.get();
            if (r.getResponse().getStatus() == 201) {
                creadas++;
            } else if ("COUPON_EXHAUSTED".equals(codigoDeError(r))) {
                agotadas++;
            }
        }
        hilos.shutdown();

        assertThat(creadas).isEqualTo(10);
        assertThat(agotadas).isEqualTo(40);
        assertThat(usos(couponId)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from coupon_redemptions where coupon_id = ? and estado = 'ACTIVO'",
                Long.class, UUID.fromString(couponId))).isEqualTo(10);
    }

    @Test
    void crudDeCuponesSegunElRol() throws Exception {
        String deTripBus = cupon(codigo(), admin(), null, null);
        String codigoPropio = codigo();
        String propio = cupon(codigoPropio, operador(empresa), null, null);
        assertThat(jdbc.queryForObject("select company_id from coupons where id = ?", UUID.class,
                UUID.fromString(propio))).isEqualTo(empresa);

        // El OPERADOR no crea cupones de otra empresa ni ve los de TripBus.
        mvc.perform(post("/coupons").with(operador(empresa)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","tipo":"VALOR","valor":1000,"companyId":"%s","vigenteDesde":"%s","vigenteHasta":"%s"}"""
                        .formatted(codigo(), UUID.randomUUID(), Instant.now(), Instant.now().plus(Duration.ofDays(1)))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/coupons/{id}", deTripBus).with(operador(empresa))).andExpect(status().isNotFound());
        mvc.perform(get("/coupons/{id}", propio).with(operador(empresa))).andExpect(status().isOk())
                .andExpect(jsonPath("$.usos").value(0));
        mvc.perform(get("/coupons").with(operador(empresa))).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/coupons").param("companyId", empresa.toString()).with(admin()))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/coupons").with(admin())).andExpect(status().isOk());
        mvc.perform(get("/coupons").with(pasajero())).andExpect(status().isForbidden());
        mvc.perform(get("/coupons").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR")))).andExpect(status().isForbidden());

        // Código único sin distinguir mayúsculas.
        mvc.perform(post("/coupons").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","tipo":"VALOR","valor":1000,"vigenteDesde":"%s","vigenteHasta":"%s"}"""
                        .formatted(codigoPropio.toLowerCase(), Instant.now(), Instant.now().plus(Duration.ofDays(1)))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COUPON_CODE_TAKEN"));
        // Validaciones.
        mvc.perform(post("/coupons").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","tipo":"PORCENTAJE","valor":150,"vigenteDesde":"%s","vigenteHasta":"%s"}"""
                        .formatted(codigo(), Instant.now(), Instant.now().plus(Duration.ofDays(1)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/coupons").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","tipo":"VALOR","valor":10,"vigenteDesde":"%s","vigenteHasta":"%s"}"""
                        .formatted(codigo(), Instant.now(), Instant.now().minus(Duration.ofDays(1)))))
                .andExpect(status().isBadRequest());

        // Editar y desactivar.
        mvc.perform(put("/coupons/{id}", propio).with(operador(empresa)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","tipo":"VALOR","valor":7000,"vigenteDesde":"%s","vigenteHasta":"%s","usosMaximos":5}"""
                        .formatted(codigoPropio, Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(5)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valor").value(7000))
                .andExpect(jsonPath("$.usosMaximos").value(5));
        JsonNode conCupon = creada(retencion(null, null, null, "2"), codigoPropio, "ADULTO");
        assertThat(conCupon.get("descuentoCupon").decimalValue()).isEqualByComparingTo("7000");
        mvc.perform(put("/coupons/{id}", propio).with(operador(empresa)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","tipo":"VALOR","valor":7000,"vigenteDesde":"%s","vigenteHasta":"%s","usosMaximos":1}"""
                        .formatted(codigoPropio, Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(5)))))
                .andExpect(status().isOk());
        mvc.perform(put("/coupons/{id}", deTripBus).with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","tipo":"VALOR","valor":7000,"vigenteDesde":"%s","vigenteHasta":"%s"}"""
                        .formatted(codigoPropio, Instant.now(), Instant.now().plus(Duration.ofDays(5)))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COUPON_CODE_TAKEN"));
        mvc.perform(put("/coupons/{id}", propio).with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"codigo":"%s","tipo":"VALOR","valor":7000,"companyId":"%s","vigenteDesde":"%s","vigenteHasta":"%s","usosMaximos":0}"""
                        .formatted(codigoPropio, empresa, Instant.now(), Instant.now().plus(Duration.ofDays(5)))))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/coupons/{id}", propio).with(operador(empresa))).andExpect(status().isNoContent());
        assertThat(codigoDeError(crear(retencion(null, null, null, "3"), codigoPropio, pasajero(), "ADULTO")))
                .isEqualTo("COUPON_NOT_FOUND");
        mvc.perform(delete("/coupons/{id}", UUID.randomUUID()).with(admin())).andExpect(status().isNotFound());
    }
}
