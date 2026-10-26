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
            case REPROGRAMADA -> {
                b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
                b.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
                b.cambiarEstado(BookingStatus.CONFIRMADA, AHORA);
                b.cambiarEstado(BookingStatus.REPROGRAMADA, AHORA);
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
            "PAGO_APROBADO, CONFIRMADA", "PAGO_APROBADO, REEMBOLSADA", "CONFIRMADA, REPROGRAMADA"})
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
            "CONFIRMADA, CONFIRMADA", "REPROGRAMADA, CONFIRMADA", "PAGO_APROBADO, REPROGRAMADA"})
    void transicionesNoPermitidasLanzanExcepcion(BookingStatus desde, BookingStatus hacia) {
        Booking b = enEstado(desde);

        assertThatThrownBy(() -> b.cambiarEstado(hacia, AHORA))
                .isInstanceOf(IllegalStateException.class)
                .isInstanceOf(InvalidBookingTransitionException.class)
                .hasMessage("Transición de reserva no permitida: " + desde + " -> " + hacia);
        assertThat(b.getEstado()).isEqualTo(desde);
    }

    @Test
    void unaReprogramacionConDiferenciaQuedaPendienteDePagoYSinDiferenciaNaceAprobada() {
        Booking origen = enEstado(BookingStatus.CONFIRMADA);
        Booking.SeatSelection seleccion = new Booking.SeatSelection(UUID.randomUUID(), UUID.randomUUID(), null,
                List.of("20"), AHORA.plusSeconds(600));

        Booking conDiferencia = Booking.reprogramacion(origen, seleccion, new BigDecimal("120000"),
                new BigDecimal("25000"), new BigDecimal("120000"), "a@b.co", AHORA);
        Booking sinDiferencia = Booking.reprogramacion(origen, seleccion, new BigDecimal("80000"),
                BigDecimal.ZERO, new BigDecimal("95000"), null, AHORA);

        assertThat(conDiferencia.getEstado()).isEqualTo(BookingStatus.PENDIENTE_PAGO);
        assertThat(conDiferencia.getTotal()).isEqualByComparingTo("25000");
        assertThat(conDiferencia.getValorPagado()).isEqualByComparingTo("120000");
        assertThat(conDiferencia.getOrigenBookingId()).isEqualTo(origen.getId());
        assertThat(conDiferencia.getUserId()).isEqualTo(origen.getUserId());
        assertThat(conDiferencia.getCompanyId()).isEqualTo(origen.getCompanyId());
        assertThat(conDiferencia.getSeatNumbers()).containsExactly("20");
        assertThat(sinDiferencia.getEstado()).isEqualTo(BookingStatus.PAGO_APROBADO);
        // En una compra normal el pasaje pagado es el total.
        assertThat(origen.getValorPagado()).isEqualByComparingTo("95000");
        assertThat(origen.getOrigenBookingId()).isNull();
    }

    @Test
    void cambiarAsientoConservaElOrdenYMarcarLaAnulacionLaDejaResuelta() {
        Booking b = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("12", "13"),
                new BigDecimal("95000"), "COP", AHORA, AHORA);

        b.cambiarAsiento("12", "20", AHORA.plusSeconds(5));
        b.marcarAnulacionResuelta(AHORA.plusSeconds(9));

        assertThat(b.getSeatNumbers()).containsExactly("20", "13");
        assertThat(b.getAnulacionResueltaEn()).isEqualTo(AHORA.plusSeconds(9));
        assertThat(b.getActualizadoEn()).isEqualTo(AHORA.plusSeconds(9));
        assertThatThrownBy(() -> b.cambiarAsiento("99", "21", AHORA)).isInstanceOf(IllegalArgumentException.class);
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
