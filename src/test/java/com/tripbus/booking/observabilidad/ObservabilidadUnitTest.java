package com.tripbus.booking.observabilidad;

import com.tripbus.booking.config.BookingMdc;
import com.tripbus.booking.config.RequestIdFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** HT-10: MDC de la reserva, métricas de negocio (nombres, etiquetas y registro tras el commit). */
class ObservabilidadUnitTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricasBooking metricas = new MetricasBooking(registry);

    @AfterEach
    void limpiar() {
        MDC.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void elBookingIdSaleDeLasRutasDeUnaReserva() {
        UUID id = UUID.randomUUID();
        assertThat(RequestIdFilter.bookingIdDe("/bookings/" + id)).isEqualTo(id.toString());
        assertThat(RequestIdFilter.bookingIdDe("/bookings/" + id + "/pay")).isEqualTo(id.toString());
        assertThat(RequestIdFilter.bookingIdDe("/bookings")).isNull();
        assertThat(RequestIdFilter.bookingIdDe("/bookings/affected")).isNull();
        assertThat(RequestIdFilter.bookingIdDe("/tickets/" + id)).isNull();
        assertThat(RequestIdFilter.bookingIdDe(null)).isNull();
    }

    @Test
    void elMdcDeLaReservaRestauraElValorAnterior() {
        UUID peticion = UUID.randomUUID();
        UUID saga = UUID.randomUUID();
        MDC.put(RequestIdFilter.MDC_BOOKING, peticion.toString());

        try (BookingMdc ignored = BookingMdc.con(saga)) {
            assertThat(MDC.get(RequestIdFilter.MDC_BOOKING)).isEqualTo(saga.toString());
        }
        assertThat(MDC.get(RequestIdFilter.MDC_BOOKING)).isEqualTo(peticion.toString());

        MDC.clear();
        try (BookingMdc ignored = BookingMdc.con(null)) {
            assertThat(MDC.get(RequestIdFilter.MDC_BOOKING)).isNull();
        }
        try (BookingMdc ignored = BookingMdc.con(saga)) {
            assertThat(MDC.get(RequestIdFilter.MDC_BOOKING)).isEqualTo(saga.toString());
        }
        assertThat(MDC.get(RequestIdFilter.MDC_BOOKING)).isNull();
    }

    @Test
    void laCausaDelReembolsoEsElMotivoHastaLosDosPuntos() {
        assertThat(MetricasBooking.causa("ASIENTO_NO_CONFIRMADO: HOLD_NOT_ACTIVE")).isEqualTo("asiento_no_confirmado");
        assertThat(MetricasBooking.causa("AFECTADA_POR_LA_EMPRESA")).isEqualTo("afectada_por_la_empresa");
        assertThat(MetricasBooking.causa(" ")).isEqualTo("desconocida");
        assertThat(MetricasBooking.causa(null)).isEqualTo("desconocida");
    }

    @Test
    void etiquetasSinValorQuedanComoDesconocido() {
        metricas.pago(null, MetricasBooking.RECHAZADO);
        metricas.tiqueteEmitido(" taquilla ");
        metricas.pagoHastaTiquete(Duration.ofSeconds(-5));

        assertThat(registry.get(MetricasBooking.PAGOS).tag("metodo", "desconocido").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MetricasBooking.TIQUETES).tag("canal", "TAQUILLA").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MetricasBooking.PAGO_HASTA_TIQUETE).timer().totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isZero();
    }

    @Test
    void conTransaccionActivaLaMedicionEsperaAlCommit() {
        TransactionSynchronizationManager.initSynchronization();
        metricas.despuesDelCommit(metricas::sagaRecuperada);
        assertThat(registry.find(MetricasBooking.SAGAS_RECUPERADAS).counter()).isNull();

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        assertThat(registry.get(MetricasBooking.SAGAS_RECUPERADAS).counter().count()).isEqualTo(1.0);
    }

    @Test
    void sinTransaccionSeMideYa() {
        metricas.despuesDelCommit(() -> metricas.reembolso("APROBADO_TARDIO"));
        assertThat(registry.get(MetricasBooking.REEMBOLSOS).tag("causa", "aprobado_tardio").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void elResultadoDeUnPagoFallidoSegunElMotivo() {
        assertThat(com.tripbus.booking.service.PaymentService.resultadoDelFallo("PASARELA_NO_DISPONIBLE"))
                .isEqualTo(MetricasBooking.PASARELA_NO_DISPONIBLE);
        assertThat(com.tripbus.booking.service.PaymentService.resultadoDelFallo("SIN_RESPUESTA_DE_LA_PASARELA"))
                .isEqualTo(MetricasBooking.SIN_RESPUESTA);
        assertThat(com.tripbus.booking.service.PaymentService.resultadoDelFallo("OTRO")).isEqualTo(MetricasBooking.RECHAZADO);
    }
}
