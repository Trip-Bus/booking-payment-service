package com.tripbus.booking.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tripbus.booking.config.PaymentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pasarela sandbox: responde de inmediato y entrega el resultado por un webhook firmado (y, si se configura, lo
 * reenvía para probar la idempotencia). Un servidor HTTP del JDK hace de booking recibiendo el webhook.
 */
class SandboxPaymentGatewayTest {

    private static final String SECRETO = "secreto-webhook";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> cuerpos = new CopyOnWriteArrayList<>();
    private final List<String> firmas = new CopyOnWriteArrayList<>();
    private String url;

    @BeforeEach
    void levantar() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhooks/payment", this::recibir);
        server.start();
        url = "http://localhost:" + server.getAddress().getPort() + "/webhooks/payment";
    }

    @AfterEach
    void detener() {
        server.stop(0);
    }

    private void recibir(HttpExchange exchange) throws IOException {
        cuerpos.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        firmas.add(exchange.getRequestHeaders().getFirst("X-Signature"));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private SandboxPaymentGateway pasarela(String webhookUrl, boolean duplicar) {
        PaymentProperties props = new PaymentProperties(webhookUrl, SECRETO, Duration.ofMillis(10), duplicar);
        return new SandboxPaymentGateway(props, new WebhookSigner(props), MAPPER);
    }

    @Test
    void apruebaYEntregaElWebhookFirmado() throws Exception {
        SandboxPaymentGateway gateway = pasarela(url, false);
        UUID paymentId = UUID.randomUUID();

        String gatewayId = gateway.charge(paymentId, new BigDecimal("95000"), "COP", "tok_visa_approved");

        await().atMost(Duration.ofSeconds(5)).until(() -> cuerpos.size() == 1);
        JsonNode evento = MAPPER.readTree(cuerpos.get(0));
        assertThat(gatewayId).startsWith("sbx_");
        assertThat(evento.get("status").asText()).isEqualTo("APPROVED");
        assertThat(evento.get("reference").asText()).isEqualTo(paymentId.toString());
        assertThat(evento.get("gatewayPaymentId").asText()).isEqualTo(gatewayId);
        assertThat(new WebhookSigner(new PaymentProperties(url, SECRETO, null, false)).verify(cuerpos.get(0),
                firmas.get(0))).isTrue();
        assertThat(gateway.refund(gatewayId, BigDecimal.TEN)).startsWith("sbx_rf_");
        gateway.shutdown();
    }

    @Test
    void rechazaLosTokensDeclinedYPuedeReenviarElMismoWebhook() throws Exception {
        SandboxPaymentGateway gateway = pasarela(url, true);

        gateway.charge(UUID.randomUUID(), BigDecimal.ONE, "COP", "tok_visa_DECLINED");

        await().atMost(Duration.ofSeconds(5)).until(() -> cuerpos.size() == 2);
        JsonNode evento = MAPPER.readTree(cuerpos.get(0));
        assertThat(evento.get("status").asText()).isEqualTo("DECLINED");
        assertThat(evento.get("reason").asText()).isEqualTo("FONDOS_INSUFICIENTES");
        assertThat(cuerpos.get(1)).isEqualTo(cuerpos.get(0)); // mismo eventId: el webhook es idempotente
        gateway.shutdown();
    }

    @Test
    void siElWebhookNoSeEntregaElCobroNoFalla() {
        SandboxPaymentGateway gateway = pasarela("http://localhost:1/webhooks/payment", false);

        assertThat(gateway.charge(UUID.randomUUID(), BigDecimal.ONE, "COP", "tok")).startsWith("sbx_");
        gateway.shutdown();
    }

    private SandboxPaymentGateway pasarelaPse(Duration pseTimeout, boolean duplicar) {
        PaymentProperties props = new PaymentProperties(url, SECRETO, Duration.ofMillis(10), duplicar,
                Duration.ofMillis(10), pseTimeout, true, null);
        return new SandboxPaymentGateway(props, new WebhookSigner(props), MAPPER);
    }

    private static PaymentGateway.ChargeRequest pse() {
        return new PaymentGateway.ChargeRequest("PSE", null, "1", null);
    }

    private static PaymentGateway.ChargeRequest nequi(String telefono) {
        return new PaymentGateway.ChargeRequest("NEQUI", null, null, telefono);
    }

    @Test
    void tarjetaConLaInterfazNuevaNoTieneRedireccion() throws Exception {
        SandboxPaymentGateway gateway = pasarela(url, false);

        PaymentGateway.ChargeResult r = gateway.charge(UUID.randomUUID(), BigDecimal.ONE, "COP",
                new PaymentGateway.ChargeRequest("TARJETA", "tok_visa_approved", null, null));

        assertThat(r.gatewayPaymentId()).startsWith("sbx_");
        assertThat(r.redirectUrl()).isNull();
        await().atMost(Duration.ofSeconds(5)).until(() -> cuerpos.size() == 1);
        assertThat(MAPPER.readTree(cuerpos.get(0)).get("status").asText()).isEqualTo("APPROVED");
        gateway.shutdown();
    }

    @Test
    void psePendienteHastaQueElBancoApruebaYSoloUnaVez() throws Exception {
        SandboxPaymentGateway gateway = pasarelaPse(Duration.ofMinutes(10), false);

        PaymentGateway.ChargeResult r = gateway.charge(UUID.randomUUID(), BigDecimal.TEN, "COP", pse());

        assertThat(r.redirectUrl()).isEqualTo("/sandbox/pse/" + r.gatewayPaymentId());
        assertThat(cuerpos).isEmpty(); // sin webhook hasta que el pasajero decide en el banco
        assertThat(gateway.completePse(r.gatewayPaymentId(), true)).isTrue();
        assertThat(gateway.completePse(r.gatewayPaymentId(), false)).isFalse();
        assertThat(gateway.completePse("sbx_desconocido", true)).isFalse();
        await().atMost(Duration.ofSeconds(5)).until(() -> cuerpos.size() == 1);
        JsonNode evento = MAPPER.readTree(cuerpos.get(0));
        assertThat(evento.get("status").asText()).isEqualTo("APPROVED");
        assertThat(evento.get("reason").isNull()).isTrue();
        gateway.shutdown();
    }

    @Test
    void pseRechazadaEnElBancoReenviaElWebhookSiSeConfigura() throws Exception {
        SandboxPaymentGateway gateway = pasarelaPse(Duration.ofMinutes(10), true);
        PaymentGateway.ChargeResult r = gateway.charge(UUID.randomUUID(), BigDecimal.TEN, "COP", pse());

        gateway.completePse(r.gatewayPaymentId(), false);

        await().atMost(Duration.ofSeconds(5)).until(() -> cuerpos.size() == 2);
        assertThat(MAPPER.readTree(cuerpos.get(0)).get("reason").asText()).isEqualTo("PSE_RECHAZADO_O_VENCIDO");
        assertThat(cuerpos.get(1)).isEqualTo(cuerpos.get(0));
        gateway.shutdown();
    }

    @Test
    void pseSinDecisionVenceComoRechazada() throws Exception {
        SandboxPaymentGateway gateway = pasarelaPse(Duration.ofMillis(10), false);
        PaymentGateway.ChargeResult r = gateway.charge(UUID.randomUUID(), BigDecimal.TEN, "COP", pse());

        await().atMost(Duration.ofSeconds(5)).until(() -> cuerpos.size() == 1);
        assertThat(MAPPER.readTree(cuerpos.get(0)).get("status").asText()).isEqualTo("DECLINED");
        assertThat(gateway.completePse(r.gatewayPaymentId(), true)).isFalse(); // ya venció
        gateway.shutdown();
    }

    @Test
    void nequiApruebaRechazaOFallaSegunElCelularDePrueba() throws Exception {
        SandboxPaymentGateway gateway = pasarelaPse(Duration.ofMinutes(10), false);

        assertThat(gateway.charge(UUID.randomUUID(), BigDecimal.ONE, "COP", nequi("3991111111")).redirectUrl())
                .isNull();
        await().atMost(Duration.ofSeconds(5)).until(() -> cuerpos.size() == 1);
        gateway.charge(UUID.randomUUID(), BigDecimal.ONE, "COP", nequi("3992222222"));
        await().atMost(Duration.ofSeconds(5)).until(() -> cuerpos.size() == 2);
        gateway.charge(UUID.randomUUID(), BigDecimal.ONE, "COP", nequi("3001234567"));
        await().atMost(Duration.ofSeconds(5)).until(() -> cuerpos.size() == 3);

        assertThat(MAPPER.readTree(cuerpos.get(0)).get("status").asText()).isEqualTo("APPROVED");
        assertThat(MAPPER.readTree(cuerpos.get(1)).get("reason").asText()).isEqualTo("PAGO_RECHAZADO_POR_NEQUI");
        assertThat(MAPPER.readTree(cuerpos.get(2)).get("reason").asText()).isEqualTo("ERROR_NEQUI");
        gateway.shutdown();
    }

    @Test
    void laFirmaVaciaOAlteradaNoSeAcepta() {
        WebhookSigner signer = new WebhookSigner(new PaymentProperties(url, SECRETO, null, false));

        assertThat(signer.verify("{}", null)).isFalse();
        assertThat(signer.verify("{}", " ")).isFalse();
        assertThat(signer.verify("{}", signer.sign("{\"a\":1}"))).isFalse();
        assertThat(signer.verify("{}", " " + signer.sign("{}") + " ")).isTrue();
    }
}
