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
    public String charge(UUID paymentId, BigDecimal amount, String currency, String cardToken) {
        String gatewayId = "sbx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        boolean declined = cardToken.toLowerCase(Locale.ROOT).contains("declined");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        event.put("gatewayPaymentId", gatewayId);
        event.put("reference", paymentId.toString());
        event.put("status", declined ? "DECLINED" : "APPROVED");
        event.put("reason", declined ? "FONDOS_INSUFICIENTES" : null);
        event.put("amount", amount);
        event.put("currency", currency);
        event.put("timestamp", Instant.now().toString());

        long delay = properties.webhookDelay().toMillis();
        scheduler.schedule(() -> sendWebhook(event), delay, TimeUnit.MILLISECONDS);
        if (properties.duplicateWebhook()) {
            scheduler.schedule(() -> sendWebhook(event), delay + 800, TimeUnit.MILLISECONDS);
        }
        log.info("[sandbox] cobro {} por {} {} -> {}", gatewayId, amount, currency, event.get("status"));
        return gatewayId;
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
