package com.tripbus.booking.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pasarela de pagos. En el MVP la implementación es un sandbox local; cambiar a una
 * pasarela real (p. ej. Wompi o PayU sandbox) es implementar esta interfaz.
 */
public interface PaymentGateway {

    /** Inicia el cobro. El resultado final llega después por webhook. Devuelve el id en la pasarela. */
    String charge(UUID paymentId, BigDecimal amount, String currency, String cardToken);

    /** Reembolsa un cobro aprobado. Devuelve el id del reembolso en la pasarela. */
    String refund(String gatewayPaymentId, BigDecimal amount);
}
