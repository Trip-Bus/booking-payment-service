package com.tripbus.booking.payment;

import com.tripbus.booking.config.PaymentProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Firma HMAC-SHA256 del cuerpo del webhook (header X-Signature). */
@Component
public class WebhookSigner {

    private final byte[] secret;

    public WebhookSigner(PaymentProperties properties) {
        this.secret = properties.webhookSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el webhook", e);
        }
    }

    public boolean verify(String body, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8));
    }
}
