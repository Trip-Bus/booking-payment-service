package com.tripbus.booking.domain;

import com.tripbus.booking.config.AffectedProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US-154/US-155: opciones según la causa y una sola decisión por reserva afectada. */
class AffectedBookingTest {

    private static final Instant AHORA = Instant.parse("2026-10-26T12:00:00Z");

    private static AffectedBooking afectada(AffectedBooking.Causa causa) {
        return new AffectedBooking(UUID.randomUUID(), UUID.randomUUID(), causa, AHORA.plusSeconds(3600), AHORA);
    }

    @Test
    void lasOpcionesDependenDeLaCausa() {
        assertThat(afectada(AffectedBooking.Causa.CANCELACION).opciones())
                .containsExactly(AffectedBooking.Opcion.REPROGRAMAR, AffectedBooking.Opcion.REEMBOLSO);
        assertThat(afectada(AffectedBooking.Causa.RETRASO).opciones()).containsExactly(
                AffectedBooking.Opcion.ACEPTAR_CAMBIO, AffectedBooking.Opcion.REPROGRAMAR, AffectedBooking.Opcion.REEMBOLSO);
        assertThat(afectada(AffectedBooking.Causa.REPROGRAMACION).opciones()).hasSize(3);
    }

    @Test
    void seDecideUnaSolaVezYUnNuevoCambioLaReabre() {
        AffectedBooking a = afectada(AffectedBooking.Causa.RETRASO);
        assertThat(a.pendiente()).isTrue();
        assertThat(a.plazoVencido(AHORA)).isFalse();
        assertThat(a.plazoVencido(AHORA.plusSeconds(3600))).isTrue();

        UUID nueva = UUID.randomUUID();
        a.decidir(AffectedBooking.Estado.REPROGRAMADA, AffectedBooking.DecididoPor.PASAJERO, nueva, AHORA);
        assertThat(a.getEstado()).isEqualTo(AffectedBooking.Estado.REPROGRAMADA);
        assertThat(a.getDecididoPor()).isEqualTo(AffectedBooking.DecididoPor.PASAJERO);
        assertThat(a.getDecididoEn()).isEqualTo(AHORA);
        assertThat(a.getBookingNuevoId()).isEqualTo(nueva);
        assertThatThrownBy(() -> a.decidir(AffectedBooking.Estado.REEMBOLSADA, AffectedBooking.DecididoPor.SISTEMA,
                null, AHORA)).isInstanceOf(IllegalStateException.class);

        a.reabrir(AffectedBooking.Causa.CANCELACION, AHORA.plusSeconds(60), AHORA.plusSeconds(1));
        assertThat(a.pendiente()).isTrue();
        assertThat(a.getCausa()).isEqualTo(AffectedBooking.Causa.CANCELACION);
        assertThat(a.getDecididoEn()).isNull();
        assertThat(a.getBookingNuevoId()).isNull();
        assertThat(a.getPlazoDecision()).isEqualTo(AHORA.plusSeconds(60));
    }

    @Test
    void laDecisionNoPuedeSerPendienteYLaAnulacionQuedaRegistrada() {
        AffectedBooking a = afectada(AffectedBooking.Causa.CANCELACION);

        assertThatThrownBy(() -> a.decidir(AffectedBooking.Estado.PENDIENTE, AffectedBooking.DecididoPor.SISTEMA,
                null, AHORA)).isInstanceOf(IllegalArgumentException.class);
        a.marcarAnulacionResuelta(AHORA);
        assertThat(a.getAnulacionResueltaEn()).isEqualTo(AHORA);
        assertThat(a.getItineraryId()).isNotNull();
    }

    @Test
    void propiedadesPorDefectoYPropias() {
        AffectedProperties defecto = new AffectedProperties(null, null);
        AffectedProperties propias = new AffectedProperties(30, Duration.ofHours(6));

        assertThat(defecto.affectedDelayMin()).isEqualTo(60);
        assertThat(defecto.affectedDecisionWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(propias.affectedDelayMin()).isEqualTo(30);
        assertThat(propias.affectedDecisionWindow()).isEqualTo(Duration.ofHours(6));
    }
}
