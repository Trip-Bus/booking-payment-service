package com.tripbus.booking.observabilidad;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Locale;

/**
 * HT-10: métricas de negocio de booking (salen en /actuator/prometheus con el tag application=booking-payment-service).
 *
 * <ul>
 *   <li>{@code tripbus_pagos_total{metodo, resultado}}: resultado final de cada cobro (aprobado, rechazado,
 *   monto_incorrecto, aprobado_tardio, pasarela_no_disponible, sin_respuesta).</li>
 *   <li>{@code tripbus_reembolsos_total{causa}}: reembolsos por causa (asiento_no_confirmado, aprobado_tardio,
 *   afectada_por_la_empresa, reprogramacion_no_aplicable...).</li>
 *   <li>{@code tripbus_sagas_recuperadas_total}: sagas interrumpidas que retomó o cerró SagaRecoveryJob.</li>
 *   <li>{@code tripbus_tiquetes_emitidos_total{canal}}: tiquetes emitidos (APP o TAQUILLA).</li>
 *   <li>{@code tripbus_pago_hasta_tiquete_segundos}: Timer desde que se registra el pago hasta que se emite el
 *   tiquete (en Prometheus, con la unidad: {@code tripbus_pago_hasta_tiquete_segundos_seconds_count/_sum/_max}).</li>
 * </ul>
 */
@Component
public class MetricasBooking {

    public static final String PAGOS = "tripbus.pagos";
    public static final String REEMBOLSOS = "tripbus.reembolsos";
    public static final String SAGAS_RECUPERADAS = "tripbus.sagas.recuperadas";
    public static final String TIQUETES = "tripbus.tiquetes.emitidos";
    public static final String PAGO_HASTA_TIQUETE = "tripbus.pago.hasta.tiquete.segundos";

    public static final String APROBADO = "aprobado";
    public static final String RECHAZADO = "rechazado";
    public static final String MONTO_INCORRECTO = "monto_incorrecto";
    public static final String APROBADO_TARDIO = "aprobado_tardio";
    public static final String PASARELA_NO_DISPONIBLE = "pasarela_no_disponible";
    public static final String SIN_RESPUESTA = "sin_respuesta";

    private final MeterRegistry registry;

    public MetricasBooking(MeterRegistry registry) {
        this.registry = registry;
    }

    public void pago(String metodo, String resultado) {
        Counter.builder(PAGOS).description("Resultado de los cobros por método de pago")
                .tag("metodo", etiqueta(metodo)).tag("resultado", resultado)
                .register(registry).increment();
    }

    /** {@code motivo} es el del reembolso (p. ej. "ASIENTO_NO_CONFIRMADO: HOLD_NOT_ACTIVE"): cuenta su causa. */
    public void reembolso(String motivo) {
        Counter.builder(REEMBOLSOS).description("Reembolsos por causa")
                .tag("causa", causa(motivo)).register(registry).increment();
    }

    public void sagaRecuperada() {
        Counter.builder(SAGAS_RECUPERADAS).description("Sagas interrumpidas que retomó o cerró el job de recuperación")
                .register(registry).increment();
    }

    public void tiqueteEmitido(String canal) {
        Counter.builder(TIQUETES).description("Tiquetes emitidos por canal")
                .tag("canal", etiqueta(canal)).register(registry).increment();
    }

    public void pagoHastaTiquete(Duration duracion) {
        Timer.builder(PAGO_HASTA_TIQUETE).description("Tiempo desde el pago hasta la emisión del tiquete")
                .register(registry).record(duracion.isNegative() ? Duration.ZERO : duracion);
    }

    /** Ejecuta la medición al confirmar la transacción en curso (o ya, si no hay una). */
    public void despuesDelCommit(Runnable medicion) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    medicion.run();
                }
            });
        } else {
            medicion.run();
        }
    }

    /** Causa del reembolso: el motivo hasta los dos puntos, en minúsculas. */
    static String causa(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            return "desconocida";
        }
        int fin = motivo.indexOf(':');
        return (fin >= 0 ? motivo.substring(0, fin) : motivo).trim().toLowerCase(Locale.ROOT);
    }

    private static String etiqueta(String valor) {
        return valor == null || valor.isBlank() ? "desconocido" : valor.trim().toUpperCase(Locale.ROOT);
    }
}
