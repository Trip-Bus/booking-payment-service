package com.tripbus.booking.domain;

import com.tripbus.booking.common.error.ErrorResponse;
import com.tripbus.booking.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingTransitionsTest {

    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");

    private static Booking reserva() {
        return new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("12"),
                new BigDecimal("95000"), "COP", AHORA.plusSeconds(600), null, UUID.randomUUID(), AHORA);
    }

    /** Lleva una reserva nueva al estado indicado siguiendo el camino real de la saga. */
    private static Booking enEstado(BookingStatus estado) {
        Booking b = reserva();
        switch (estado) {
            case PENDIENTE_PAGO -> { }
            case EXPIRADA -> b.cambiarEstado(BookingStatus.EXPIRADA, AHORA);
            case PAGO_EN_PROCESO -> b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
            case PAGO_RECHAZADO -> {
                b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
                b.cambiarEstado(BookingStatus.PAGO_RECHAZADO, AHORA);
            }
            case PAGO_APROBADO -> {
                b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
                b.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
            }
            case CONFIRMADA, REEMBOLSADA -> {
                b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
                b.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
                b.cambiarEstado(estado, AHORA);
            }
        }
        return b;
    }

    @Test
    void laCompraCompletaRecorreLasTransicionesPermitidas() {
        Booking b = enEstado(BookingStatus.CONFIRMADA);

        assertThat(b.getEstado()).isEqualTo(BookingStatus.CONFIRMADA);
        assertThat(b.getActualizadoEn()).isEqualTo(AHORA);
        assertThat(b.getCompanyId()).isNotNull();
    }

    @ParameterizedTest(name = "{0} -> {1} permitida")
    @CsvSource({
            "PENDIENTE_PAGO, PAGO_EN_PROCESO", "PENDIENTE_PAGO, EXPIRADA",
            "PAGO_EN_PROCESO, PAGO_APROBADO", "PAGO_EN_PROCESO, PAGO_RECHAZADO",
            "PAGO_APROBADO, CONFIRMADA", "PAGO_APROBADO, REEMBOLSADA"})
    void transicionesPermitidas(BookingStatus desde, BookingStatus hacia) {
        Booking b = enEstado(desde);

        b.cambiarEstado(hacia, AHORA);

        assertThat(b.getEstado()).isEqualTo(hacia);
    }

    @ParameterizedTest(name = "{0} -> {1} no permitida")
    @CsvSource({
            "PENDIENTE_PAGO, CONFIRMADA", "PENDIENTE_PAGO, PAGO_APROBADO", "PAGO_EN_PROCESO, CONFIRMADA",
            "PAGO_EN_PROCESO, EXPIRADA", "PAGO_APROBADO, PAGO_RECHAZADO", "CONFIRMADA, REEMBOLSADA",
            "PAGO_RECHAZADO, PAGO_APROBADO", "EXPIRADA, PAGO_EN_PROCESO", "REEMBOLSADA, CONFIRMADA",
            "CONFIRMADA, CONFIRMADA"})
    void transicionesNoPermitidasLanzanExcepcion(BookingStatus desde, BookingStatus hacia) {
        Booking b = enEstado(desde);

        assertThatThrownBy(() -> b.cambiarEstado(hacia, AHORA))
                .isInstanceOf(IllegalStateException.class)
                .isInstanceOf(InvalidBookingTransitionException.class)
                .hasMessage("Transición de reserva no permitida: " + desde + " -> " + hacia);
        assertThat(b.getEstado()).isEqualTo(desde);
    }

    @Test
    void laApiTraduceLaTransicionInvalidaA409() {
        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler().handleInvalidTransition(
                new InvalidBookingTransitionException(BookingStatus.CONFIRMADA, BookingStatus.REEMBOLSADA));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("INVALID_BOOKING_TRANSITION");
        assertThat(response.getBody().error().details()).containsEntry("desde", "CONFIRMADA")
                .containsEntry("hacia", "REEMBOLSADA");
    }
}
