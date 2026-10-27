package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.RemoteErrors;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.common.redis.LeaderLock;
import com.tripbus.booking.domain.AffectedBooking;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.AffectedBookingRepository;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.BookingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-155: decisión automática al vencer el plazo, con catalog y seat-inventory simulados. */
class AffectedBookingJobTest {

    private static final Instant AHORA = Instant.parse("2026-10-26T12:00:00Z");
    private static final UUID EMPRESA = UUID.randomUUID();

    private final AffectedBookingRepository affected = mock(AffectedBookingRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final AffectedBookingService affectedService = mock(AffectedBookingService.class);
    private final AffectedRefundService refundService = mock(AffectedRefundService.class);
    private final RescheduleService rescheduleService = mock(RescheduleService.class);
    private final LeaderLock leaderLock = mock(LeaderLock.class);
    private AffectedBookingJob job;
    private Booking booking;
    private AffectedBooking afectada;
    private Ticket ticket;
    private final UUID otroViaje = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        job = new AffectedBookingJob(affected, bookings, tickets, catalog, seatInventory, affectedService,
                refundService, rescheduleService, mock(AffectedMailService.class), mock(EventPublisher.class),
                leaderLock, Clock.fixed(AHORA, ZoneOffset.UTC));
        booking = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("12"),
                BigDecimal.TEN, "COP", AHORA, null, EMPRESA, AHORA);
        booking.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
        booking.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
        booking.cambiarEstado(BookingStatus.CONFIRMADA, AHORA);
        afectada = new AffectedBooking(booking.getId(), booking.getItineraryId(), AffectedBooking.Causa.CANCELACION,
                AHORA.minusSeconds(60), AHORA);
        ticket = mock(Ticket.class);
        when(ticket.getEstado()).thenReturn("EMITIDO");
        when(affected.findById(booking.getId())).thenReturn(Optional.of(afectada));
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(tickets.findByBookingId(booking.getId())).thenReturn(Optional.of(ticket));
        when(catalog.itinerary(booking.getItineraryId())).thenReturn(viaje(booking.getItineraryId(), EMPRESA,
                AHORA.plus(Duration.ofHours(5)), "CANCELADO"));
        when(catalog.itinerary(otroViaje)).thenReturn(viaje(otroViaje, EMPRESA, AHORA.plus(Duration.ofHours(9)),
                "PROGRAMADO"));
        when(catalog.search(anyString(), anyString(), any())).thenReturn(List.of(new CatalogClient.TripOption(otroViaje,
                UUID.randomUUID(), "Bogotá", "Medellín", AHORA.plus(Duration.ofHours(9)), null)));
        when(seatInventory.availableSeats(otroViaje)).thenReturn(List.of("1", "2"));
        when(seatInventory.createHold(eq(otroViaje), any(), any())).thenReturn(new SeatInventoryClient.Hold(
                UUID.randomUUID(), otroViaje, List.of("1"), booking.getUserId(), null, null, "ACTIVA", AHORA, 600));
    }

    private static CatalogClient.ItinerarySummary viaje(UUID id, UUID empresa, Instant salida, String estado) {
        return new CatalogClient.ItinerarySummary(id, "Bogotá", "Medellín", salida, null, "Expreso", "ABC", empresa,
                estado);
    }

    private static BookingService.CreateResult resultado(String estado) {
        return new BookingService.CreateResult(new BookingResponse(UUID.randomUUID(), estado, null, null, null,
                List.of("1"), List.of(), null, null, null, null, null, UUID.randomUUID(), null, null, null), true);
    }

    @Test
    void elJobCorreEnUnSoloNodoYUnFalloNoDetieneLaPasada() {
        UUID otra = UUID.randomUUID();
        when(affected.findIdsConPlazoVencido(AHORA)).thenReturn(List.of(otra, booking.getId()));
        when(affected.findById(otra)).thenThrow(new IllegalStateException("BD"));
        when(affected.findIdsConAnulacionPendiente(AHORA.minusSeconds(15))).thenReturn(List.of(booking.getId()));
        when(rescheduleService.rescheduleAffected(any(), any(), any())).thenReturn(resultado("CONFIRMADA"));

        job.runNow();
        job.run();

        verify(affectedService).registrarNuevaReserva(eq(booking.getId()), any(), eq(AffectedBooking.Estado.REUBICADA),
                eq(AffectedBooking.DecididoPor.SISTEMA));
        verify(refundService).anularAsientos(booking.getId());
        verify(leaderLock).runIfLeader(eq("booking-affected-decisions"), any(), any());
    }

    @Test
    void siElPasajeroYaResolvioPorSuCuentaSoloSeRegistra() {
        booking.cambiarEstado(BookingStatus.REPROGRAMADA, AHORA);
        job.decidirPorSistema(booking.getId());
        verify(affectedService).cerrar(booking.getId(), AffectedBooking.Estado.REPROGRAMADA);

        Booking abordada = new Booking(UUID.randomUUID(), booking.getUserId(), booking.getItineraryId(), null,
                List.of("1"), BigDecimal.TEN, "COP", AHORA, AHORA);
        abordada.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
        abordada.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
        abordada.cambiarEstado(BookingStatus.CONFIRMADA, AHORA);
        Ticket usado = mock(Ticket.class);
        when(usado.getEstado()).thenReturn("USADO");
        when(bookings.findById(abordada.getId())).thenReturn(Optional.of(abordada));
        when(affected.findById(abordada.getId())).thenReturn(Optional.of(new AffectedBooking(abordada.getId(),
                abordada.getItineraryId(), AffectedBooking.Causa.RETRASO, AHORA, AHORA)));
        when(tickets.findByBookingId(abordada.getId())).thenReturn(Optional.of(usado));
        job.decidirPorSistema(abordada.getId());
        verify(affectedService).cerrar(abordada.getId(), AffectedBooking.Estado.ACEPTO_CAMBIO);
        verify(refundService, never()).reembolsar(any(), any());
    }

    @Test
    void yaDecididaOSinReservaNoHaceNada() {
        afectada.decidir(AffectedBooking.Estado.REEMBOLSADA, AffectedBooking.DecididoPor.PASAJERO, null, AHORA);
        job.decidirPorSistema(booking.getId());
        job.decidirPorSistema(UUID.randomUUID());

        verify(affectedService, never()).aceptar(any(), any());
        verify(refundService, never()).reembolsar(any(), any());
    }

    @Test
    void siLaReubicacionFallaSeLiberaLaRetencionYSinOtraOpcionSeReembolsa() {
        when(rescheduleService.rescheduleAffected(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "vencida"));

        job.decidirPorSistema(booking.getId());

        verify(seatInventory).releaseHold(any(), eq("REUBICACION_FALLIDA"));
        verify(refundService).reembolsar(booking.getId(), AffectedBooking.DecididoPor.SISTEMA);
    }

    @Test
    void unaReubicacionQueNoQuedaConfirmadaOAsientosQueSeOcupanPasanAlSiguiente() {
        when(rescheduleService.rescheduleAffected(any(), any(), any())).thenReturn(resultado("REEMBOLSADA"));
        assertThat(job.reubicar(booking)).isEmpty();

        doThrow(new ApiException(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE", "ocupado"))
                .when(seatInventory).createHold(any(), any(), any());
        assertThat(job.reubicar(booking)).isEmpty();

        when(seatInventory.availableSeats(otroViaje)).thenReturn(List.of());
        assertThat(job.reubicar(booking)).isEmpty();
    }

    @Test
    void soloViajesDeLaMismaEmpresaNoCanceladosYDentroDeLaVentana() {
        when(catalog.itinerary(otroViaje)).thenReturn(viaje(otroViaje, UUID.randomUUID(), AHORA, "PROGRAMADO"));
        assertThat(job.reubicar(booking)).isEmpty();
        when(catalog.itinerary(otroViaje)).thenReturn(viaje(otroViaje, EMPRESA, AHORA, "CANCELADO"));
        assertThat(job.reubicar(booking)).isEmpty();
        when(catalog.itinerary(otroViaje)).thenReturn(null);
        assertThat(job.reubicar(booking)).isEmpty();
        verify(seatInventory, never()).availableSeats(any());

        // El original ya salió hace más de 3 días: no hay ventana.
        when(catalog.itinerary(booking.getItineraryId())).thenReturn(viaje(booking.getItineraryId(), EMPRESA,
                AHORA.minus(Duration.ofDays(4)), "CANCELADO"));
        assertThat(job.reubicar(booking)).isEmpty();
    }

    @Test
    void sinElViajeOriginalEnCatalogNoSeDecide() {
        when(catalog.itinerary(booking.getItineraryId())).thenReturn(null);

        assertThatThrownBy(() -> job.reubicar(booking)).isInstanceOf(RemoteErrors.RemoteUnavailableException.class);
        when(catalog.itinerary(booking.getItineraryId())).thenReturn(viaje(booking.getItineraryId(), null, null, null));
        assertThatThrownBy(() -> job.reubicar(booking)).isInstanceOf(RemoteErrors.RemoteUnavailableException.class);
    }

    @Test
    void sinEmpresaEnCatalogSeUsaLaDeLaReservaYSiDeshacerFallaSigue() {
        when(catalog.itinerary(booking.getItineraryId())).thenReturn(viaje(booking.getItineraryId(), null,
                AHORA.plus(Duration.ofHours(5)), "CANCELADO"));
        when(rescheduleService.rescheduleAffected(any(), any(), any())).thenReturn(resultado("PAGO_APROBADO"));
        assertThat(job.reubicar(booking)).isPresent();

        when(rescheduleService.rescheduleAffected(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "X", "x"));
        doThrow(new RemoteErrors.RemoteUnavailableException("caído", null))
                .when(seatInventory).releaseHold(any(), any());
        assertThat(job.reubicar(booking)).isEmpty();
    }
}
