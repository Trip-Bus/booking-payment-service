package com.tripbus.booking.service;

import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.config.NoShowProperties;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-145: reglas de "mis viajes" (qué se puede hacer con cada viaje) y validación de la consulta. */
class MyTripsServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-30T15:00:00Z");

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final RescheduleService reschedule = mock(RescheduleService.class);
    private final BookingProperties properties = new BookingProperties(null, null, null, null, "s");
    private MyTripsService service;

    @BeforeEach
    void preparar() {
        service = servicio(NoShowProperties.defaults());
    }

    private MyTripsService servicio(NoShowProperties noShow) {
        return new MyTripsService(bookings, tickets, reschedule, properties, noShow, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private static Booking reserva(Instant salida, UUID offerId, boolean confirmada) {
        Booking b = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), offerId, List.of("1"),
                BigDecimal.TEN, "COP", AHORA, AHORA);
        b.asignarViaje(new Booking.Viaje("A", "B", salida, "E"));
        if (confirmada) {
            b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
            b.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
            b.cambiarEstado(BookingStatus.CONFIRMADA, AHORA);
        }
        return b;
    }

    @Test
    void unTiqueteEmitidoSeReprogramaConMasDeTresHorasYSeCambiaDeAsientoConMasDeMediaHora() {
        Booking b = reserva(AHORA.plus(Duration.ofHours(4)), null, true);
        Booking pronto = reserva(AHORA.plus(Duration.ofHours(1)), null, true);
        Booking yaSalio = reserva(AHORA.minus(Duration.ofMinutes(5)), null, true);

        assertThat(service.puedeReprogramar(b, "EMITIDO", AHORA)).isTrue();
        assertThat(service.puedeReprogramar(pronto, "EMITIDO", AHORA)).isFalse();
        assertThat(MyTripsService.puedeCambiarAsiento(pronto, "EMITIDO", AHORA)).isTrue();
        assertThat(MyTripsService.puedeCambiarAsiento(yaSalio, "EMITIDO", AHORA)).isFalse();
        assertThat(MyTripsService.puedeCambiarAsiento(b, "USADO", AHORA)).isFalse();
        assertThat(MyTripsService.puedeCambiarAsiento(reserva(null, null, true), "EMITIDO", AHORA)).isFalse();
        assertThat(MyTripsService.puedeCambiarAsiento(reserva(AHORA.plus(Duration.ofDays(1)), null, false), "EMITIDO",
                AHORA)).isFalse();
    }

    @Test
    void elLimiteDeCambiosOUnaReprogramacionVivaLoImpiden() {
        Booking b = reserva(AHORA.plus(Duration.ofDays(1)), null, true);
        when(reschedule.reprogramaciones(b)).thenReturn(2);
        assertThat(service.puedeReprogramar(b, "EMITIDO", AHORA)).isFalse();

        when(reschedule.reprogramaciones(b)).thenReturn(0);
        when(bookings.existsByOrigenBookingIdAndEstadoIn(eq(b.getId()), any())).thenReturn(true);
        assertThat(service.puedeReprogramar(b, "EMITIDO", AHORA)).isFalse();
    }

    @Test
    void unNoShowSoloDentroDeLaVentanaSinOfertaYConLaPoliticaActiva() {
        Booking reciente = reserva(AHORA.minus(Duration.ofHours(2)), null, true);
        Booking viejo = reserva(AHORA.minus(Duration.ofHours(30)), null, true);
        Booking deOferta = reserva(AHORA.minus(Duration.ofHours(2)), UUID.randomUUID(), true);

        assertThat(service.puedeReprogramar(reciente, "NO_SHOW", AHORA)).isTrue();
        assertThat(service.puedeReprogramar(viejo, "NO_SHOW", AHORA)).isFalse();
        assertThat(service.puedeReprogramar(deOferta, "NO_SHOW", AHORA)).isFalse();
        assertThat(servicio(new NoShowProperties(null, false, null, null)).puedeReprogramar(reciente, "NO_SHOW", AHORA))
                .isFalse();
    }

    @Test
    void otrosEstadosSinSalidaONoConfirmadasNoSeReprograman() {
        assertThat(service.puedeReprogramar(reserva(AHORA.plus(Duration.ofDays(1)), null, true), "USADO", AHORA))
                .isFalse();
        assertThat(service.puedeReprogramar(reserva(null, null, true), "EMITIDO", AHORA)).isFalse();
        assertThat(service.puedeReprogramar(reserva(AHORA.plus(Duration.ofDays(1)), null, false), "EMITIDO", AHORA))
                .isFalse();
        verify(reschedule, never()).reprogramaciones(any());
    }

    @Test
    void scopeYPaginaInvalidosSon400YUnaPaginaVaciaNoConsultaTiquetes() {
        assertThat(MyTripsService.scope(" Pasados ")).isEqualTo(MyTripsService.Scope.PASADOS);
        assertThatThrownBy(() -> MyTripsService.scope("ayer")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("INVALID_SCOPE"));
        UUID user = UUID.randomUUID();
        for (int[] pagina : new int[][] {{-1, 10}, {0, 0}, {0, 51}}) {
            assertThatThrownBy(() -> service.list(user, MyTripsService.Scope.PROXIMOS, pagina[0], pagina[1]))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_PAGE"));
        }
        when(bookings.findPasados(eq(user), any(), any(), any())).thenReturn(Page.empty());

        assertThat(service.list(user, MyTripsService.Scope.PASADOS, 0, 10).items()).isEmpty();
        verify(tickets, never()).findByBookingIdIn(any());
    }
}
