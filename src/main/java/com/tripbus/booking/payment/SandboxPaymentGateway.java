package com.tripbus.booking.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.config.PaymentProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pasarela sandbox: acepta un token de tarjeta (nunca el número), responde de inmediato con
 * un id de transacción y, como una pasarela real, informa el resultado de forma asíncrona
 * llamando al webhook firmado. Puede reenviar el mismo webhook para probar la idempotencia.
 *
 * <p>Tokens de prueba: cualquier token aprueba, salvo los que contienen "declined"
 * (p. ej. {@code tok_visa_declined}), que se rechazan por fondos insuficientes.
 */
@Component
public class SandboxPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SandboxPaymentGateway.class);

    private final PaymentProperties properties;
    private final WebhookSigner signer;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, Map<String, Object>> pendingPse = new ConcurrentHashMap<>();

    public SandboxPaymentGateway(PaymentProperties properties, WebhookSigner signer, ObjectMapper objectMapper) {
        this.properties = properties;
        this.signer = signer;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(10000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public ChargeResult charge(UUID paymentId, BigDecimal amount, String currency, ChargeRequest request) {
        String gatewayId = "sbx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String method = request.method();
        boolean declined = "TARJETA".equals(method) && request.cardToken().toLowerCase(Locale.ROOT).contains("declined");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        event.put("gatewayPaymentId", gatewayId);
        event.put("reference", paymentId.toString());
        event.put("status", declined ? "DECLINED" : "APPROVED");
        event.put("reason", declined ? "FONDOS_INSUFICIENTES" : null);
        event.put("amount", amount);
        event.put("currency", currency);
        event.put("timestamp", Instant.now().toString());

        if ("PSE".equals(method)) {
            pendingPse.put(gatewayId, event);
            scheduler.schedule(() -> completePse(gatewayId, false), properties.pseTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new ChargeResult(gatewayId, "/sandbox/pse/" + gatewayId);
        }
        if ("NEQUI".equals(method)) {
            String phone = request.phone();
            event.put("status", "3991111111".equals(phone) ? "APPROVED" : "DECLINED");
            event.put("reason", "3992222222".equals(phone) ? "PAGO_RECHAZADO_POR_NEQUI" :
                    ("3991111111".equals(phone) ? null : "ERROR_NEQUI"));
        }
        long delay = "NEQUI".equals(method) ? properties.nequiDelay().toMillis() : properties.webhookDelay().toMillis();
        scheduler.schedule(() -> sendWebhook(event), delay, TimeUnit.MILLISECONDS);
        if (properties.duplicateWebhook()) {
            scheduler.schedule(() -> sendWebhook(event), delay + 800, TimeUnit.MILLISECONDS);
        }
        log.info("[sandbox] cobro {} por {} {} -> {}", gatewayId, amount, currency, event.get("status"));
        return new ChargeResult(gatewayId, null);
    }

    /** La p\u00e1gina de banco de pruebas completa una PSE una sola vez. */
    public boolean completePse(String gatewayPaymentId, boolean approved) {
        Map<String, Object> event = pendingPse.remove(gatewayPaymentId);
        if (event == null) return false;
        event.put("status", approved ? "APPROVED" : "DECLINED");
        event.put("reason", approved ? null : "PSE_RECHAZADO_O_VENCIDO");
        sendWebhook(event);
        if (properties.duplicateWebhook()) {
            scheduler.schedule(() -> sendWebhook(event), 800, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    @Override
    public String refund(String gatewayPaymentId, BigDecimal amount) {
        String refundId = "sbx_rf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("[sandbox] reembolso {} de {} por {}", refundId, gatewayPaymentId, amount);
        return refundId;
    }

    private void sendWebhook(Map<String, Object> event) {
        try {
            String body = objectMapper.writeValueAsString(event);
            restClient.post()
                    .uri(properties.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", signer.sign(body))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("[sandbox] no se pudo entregar el webhook {}: {}", event.get("eventId"), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
