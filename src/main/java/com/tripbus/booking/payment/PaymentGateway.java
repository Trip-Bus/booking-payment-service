package com.tripbus.booking.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pasarela de pagos. En el MVP la implementación es un sandbox local; cambiar a una
 * pasarela real (p. ej. Wompi o PayU sandbox) es implementar esta interfaz.
 */
public interface PaymentGateway {

    /** Inicia el cobro. El resultado final llega después por webhook. Devuelve el id en la pasarela. */
    /** Una pasarela real (Wompi o PayU) se conecta implementando esta interfaz. */
    ChargeResult charge(UUID paymentId, BigDecimal amount, String currency, ChargeRequest request);

    /** Compatibilidad temporal con integraciones antiguas de tarjeta. */
    default String charge(UUID paymentId, BigDecimal amount, String currency, String cardToken) {
        return charge(paymentId, amount, currency, new ChargeRequest("TARJETA", cardToken, null, null)).gatewayPaymentId();
    }

    record ChargeRequest(String method, String cardToken, String bankCode, String phone) { }
    record ChargeResult(String gatewayPaymentId, String redirectUrl) { }

    /** Reembolsa un cobro aprobado. Devuelve el id del reembolso en la pasarela. */
    String refund(String gatewayPaymentId, BigDecimal amount);
}
