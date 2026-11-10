package com.tripbus.booking.service;

import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.config.AffectedProperties;
import com.tripbus.booking.domain.AffectedBooking;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.repository.AffectedBookingRepository;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.BookingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-154: registro de reservas afectadas y reglas de la decisión, con los repositorios simulados. */
class AffectedBookingServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-26T12:00:00Z");

    private final AffectedBookingRepository affected = mock(AffectedBookingRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final RescheduleService rescheduleService = mock(RescheduleService.class);
    private final AffectedRefundService refundService = mock(AffectedRefundService.class);
    private final AffectedMailService mail = mock(AffectedMailService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final UUID viaje = UUID.randomUUID();
    private AffectedBookingService service;
    private Booking booking;
    private BookingViewer dueno;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        service = new AffectedBookingService(affected, bookings, mock(TicketRepository.class), rescheduleService,
                refundService, mail, mock(SagaLogger.class), mock(EventPublisher.class), tx,
                new AffectedProperties(null, null), Clock.fixed(AHORA, ZoneOffset.UTC));
        doAnswer(inv -> {
            inv.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any(Consumer.class));
        booking = new Booking(UUID.randomUUID(), UUID.randomUUID(), viaje, null, List.of("1"), BigDecimal.TEN, "COP",
                AHORA, AHORA);
        dueno = new BookingViewer(booking.getUserId(), false, false, null);
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookings.findConfirmadasConTiqueteEmitido(viaje)).thenReturn(List.of(booking));
        when(affected.save(any(AffectedBooking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AffectedBooking existente(AffectedBooking.Causa causa) {
        AffectedBooking a = new AffectedBooking(booking.getId(), viaje, causa, AHORA.plusSeconds(3600), AHORA);
        when(affected.findById(booking.getId())).thenReturn(Optional.of(a));
        when(affected.lockById(booking.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void soloLaReprogramacionYElRetrasoDesdeElUmbralAfectan() {
        assertThat(service.onItineraryRescheduled(viaje, "RETRASO", 59)).isZero();
        assertThat(service.onItineraryRescheduled(viaje, "OTRO", 500)).isZero();
        assertThat(service.onItineraryRescheduled(viaje, "RETRASO", 60)).isEqualTo(1);
        assertThat(service.onItineraryRescheduled(viaje, "REPROGRAMACION", -30)).isEqualTo(1);
        verify(mail, org.mockito.Mockito.times(2)).notificarAfectada(any(), any());
    }

    @Test
    void unCambioPosteriorNoReemplazaUnaCancelacionPendienteYSiReabreUnaAceptada() {
        existente(AffectedBooking.Causa.CANCELACION);
        assertThat(service.onItineraryRescheduled(viaje, "REPROGRAMACION", 30)).isZero();

        AffectedBooking aceptada = existente(AffectedBooking.Causa.RETRASO);
        aceptada.decidir(AffectedBooking.Estado.ACEPTO_CAMBIO, AffectedBooking.DecididoPor.PASAJERO, null, AHORA);
        assertThat(service.onItineraryCancelled(viaje)).isEqualTo(1);
        assertThat(aceptada.pendiente()).isTrue();
        assertThat(aceptada.getCausa()).isEqualTo(AffectedBooking.Causa.CANCELACION);
        verify(affected, never()).save(any());
    }

    @Test
    void noSePuedeDecidirConElPlazoVencidoNiAceptarUnaCancelacionNiDecidirDosVeces() {
        AffectedBooking vencida = new AffectedBooking(booking.getId(), viaje, AffectedBooking.Causa.RETRASO,
                AHORA.minusSeconds(1), AHORA);
        when(affected.findById(booking.getId())).thenReturn(Optional.of(vencida));
        assertThatThrownBy(() -> service.accept(booking.getId(), dueno)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("DECISION_WINDOW_CLOSED"));

        existente(AffectedBooking.Causa.CANCELACION);
        assertThatThrownBy(() -> service.accept(booking.getId(), dueno)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("OPTION_NOT_AVAILABLE"));

        AffectedBooking decidida = existente(AffectedBooking.Causa.RETRASO);
        decidida.decidir(AffectedBooking.Estado.ACEPTO_CAMBIO, AffectedBooking.DecididoPor.SISTEMA, null, AHORA);
        assertThatThrownBy(() -> service.aceptar(booking.getId(), AffectedBooking.DecididoPor.SISTEMA))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ALREADY_DECIDED"));
        assertThatThrownBy(() -> service.reschedule(booking.getId(), UUID.randomUUID(), dueno))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void reembolsoRepetidoDevuelveElMismoResultado() {
        AffectedBooking reembolsada = existente(AffectedBooking.Causa.CANCELACION);
        reembolsada.decidir(AffectedBooking.Estado.REEMBOLSADA, AffectedBooking.DecididoPor.PASAJERO, null, AHORA);

        assertThat(service.refund(booking.getId(), dueno).estado()).isEqualTo("REEMBOLSADA");
        verify(refundService, never()).reembolsar(any(), any());
    }

    @Test
    void siLaReservaNuevaNoQuedoConfirmadaLaAfectadaSiguePendienteY409() {
        AffectedBooking a = existente(AffectedBooking.Causa.CANCELACION);
        BookingResponse fallida = new BookingResponse(UUID.randomUUID(), "REEMBOLSADA", null, null, null, List.of(),
                List.of(), null, null, null, null, null, null, null, null, null);
        when(rescheduleService.rescheduleAffected(any(), any(), any()))
                .thenReturn(new BookingService.CreateResult(fallida, true));

        assertThatThrownBy(() -> service.reschedule(booking.getId(), UUID.randomUUID(), dueno))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("RESCHEDULE_FAILED"));
        assertThat(a.pendiente()).isTrue();
    }

    @Test
    void cerrarRegistraLoQueYaHizoElPasajero() {
        AffectedBooking a = existente(AffectedBooking.Causa.RETRASO);
        Booking nueva = mock(Booking.class);
        UUID nuevaId = UUID.randomUUID();
        when(nueva.getId()).thenReturn(nuevaId);
        when(bookings.findFirstByOrigenBookingIdOrderByCreadoEnDesc(booking.getId())).thenReturn(Optional.of(nueva));

        service.cerrar(booking.getId(), AffectedBooking.Estado.REPROGRAMADA);
        assertThat(a.getEstado()).isEqualTo(AffectedBooking.Estado.REPROGRAMADA);
        assertThat(a.getBookingNuevoId()).isEqualTo(nuevaId);
        assertThat(a.getDecididoPor()).isEqualTo(AffectedBooking.DecididoPor.PASAJERO);

        // Ya decidida: cerrar otra vez no cambia nada.
        service.cerrar(booking.getId(), AffectedBooking.Estado.ACEPTO_CAMBIO);
        assertThat(a.getEstado()).isEqualTo(AffectedBooking.Estado.REPROGRAMADA);
    }

    @Test
    void soloElDuenoDecideYOtroUsuarioNoVeLaReserva() {
        existente(AffectedBooking.Causa.RETRASO);
        BookingViewer admin = new BookingViewer(UUID.randomUUID(), true, false, null);
        BookingViewer otro = new BookingViewer(UUID.randomUUID(), false, false, null);

        assertThatThrownBy(() -> service.accept(booking.getId(), admin)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("NOT_BOOKING_OWNER"));
        assertThatThrownBy(() -> service.get(booking.getId(), otro)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("BOOKING_NOT_FOUND"));
        assertThat(service.get(booking.getId(), admin).causa()).isEqualTo("RETRASO");
    }
}
