package com.tripbus.booking.web;

import com.tripbus.booking.service.PaymentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Webhook de la pasarela: firma validada antes de procesar, idempotente ante reintentos. */
@RestController
public class WebhookController {

    private final PaymentService paymentService;

    public WebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping(value = "/webhooks/payment", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> webhook(@RequestBody String rawBody,
                                       @RequestHeader(value = "X-Signature", required = false) String signature) {
        PaymentService.WebhookOutcome outcome = paymentService.handleWebhook(rawBody, signature);
        return Map.of("resultado", outcome.name());
    }
}
