package com.tripbus.booking.service;

import com.tripbus.booking.client.RemoteErrors;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.domain.AffectedBooking;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.Refund;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.repository.AffectedBookingRepository;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.RefundRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-154/US-155: reembolso de una reserva afectada (cadena de pagos, idempotencia y anulación de asientos). */
class AffectedRefundServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-26T12:00:00Z");

    private final AffectedBookingRepository affected = mock(AffectedBookingRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final SagaLogger saga = mock(SagaLogger.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private AffectedRefundService service;
    private Booking anterior;
    private Booking booking;
    private AffectedBooking afectada;
    private Ticket ticket;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        service = new AffectedRefundService(affected, bookings, tickets, payments, refunds, gateway, seatInventory, saga,
                mock(EventPublisher.class), tx, Clock.fixed(AHORA, ZoneOffset.UTC));
        when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        doAnswer(inv -> {
            inv.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any(Consumer.class));
        anterior = confirmada();
        booking = Booking.reprogramacion(anterior, new Booking.SeatSelection(UUID.randomUUID(), UUID.randomUUID(), null,
                List.of("5"), AHORA), new BigDecimal("120000"), new BigDecimal("25000"), new BigDecimal("120000"), null,
                AHORA);
        booking.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
        booking.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
        booking.cambiarEstado(BookingStatus.CONFIRMADA, AHORA);
        afectada = new AffectedBooking(booking.getId(), booking.getItineraryId(), AffectedBooking.Causa.RETRASO,
                AHORA.plusSeconds(3600), AHORA);
        ticket = mock(Ticket.class);
        when(ticket.getId()).thenReturn(UUID.randomUUID());
        when(affected.lockById(booking.getId())).thenReturn(Optional.of(afectada));
        when(affected.findById(booking.getId())).thenReturn(Optional.of(afectada));
        when(bookings.lockById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookings.findById(anterior.getId())).thenReturn(Optional.of(anterior));
        when(tickets.findByBookingId(booking.getId())).thenReturn(Optional.of(ticket));
        when(tickets.anular(ticket.getId())).thenReturn(1);
        when(refunds.findByPaymentId(any())).thenReturn(Optional.empty());
        when(refunds.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Booking confirmada() {
        Booking b = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("12"),
                new BigDecimal("95000"), "COP", AHORA, AHORA);
        b.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, AHORA);
        b.cambiarEstado(BookingStatus.PAGO_APROBADO, AHORA);
        b.cambiarEstado(BookingStatus.CONFIRMADA, AHORA);
        return b;
    }

    private static Payment aprobado(Booking b, String monto) {
        Payment p = new Payment(b.getId(), new BigDecimal(monto), "k" + UUID.randomUUID(), "4242", "VISA", AHORA);
        p.asignarGatewayId("sbx_" + UUID.randomUUID(), AHORA);
        p.aprobar(AHORA);
        return p;
    }

    @Test
    void reembolsaTodoLoPagadoEnLaCadenaYAnulaLosAsientos() {
        Payment original = aprobado(anterior, "95000");
        Payment diferencia = aprobado(booking, "25000");
        Payment yaReembolsado = aprobado(booking, "1000");
        when(payments.findByBookingIdOrderByCreadoEnDesc(anterior.getId())).thenReturn(List.of(original));
        when(payments.findByBookingIdOrderByCreadoEnDesc(booking.getId())).thenReturn(List.of(diferencia, yaReembolsado));
        when(refunds.findByPaymentId(yaReembolsado.getId())).thenReturn(Optional.of(mock(Refund.class)));

        service.reembolsar(booking.getId(), AffectedBooking.DecididoPor.PASAJERO);

        verify(gateway).refund(original.getPaymentGatewayId(), new BigDecimal("95000"));
        verify(gateway).refund(diferencia.getPaymentGatewayId(), new BigDecimal("25000"));
        verify(gateway, never()).refund(eq(yaReembolsado.getPaymentGatewayId()), any());
        assertThat(booking.getEstado()).isEqualTo(BookingStatus.REEMBOLSADA);
        assertThat(afectada.getEstado()).isEqualTo(AffectedBooking.Estado.REEMBOLSADA);
        verify(seatInventory).voidSale(booking.getItineraryId(), booking.getId(), List.of("5"), "REEMBOLSO");
        assertThat(afectada.getAnulacionResueltaEn()).isEqualTo(AHORA);
    }

    @Test
    void yaReembolsadaNoHaceNadaYOtraDecisionEs409() {
        afectada.decidir(AffectedBooking.Estado.REEMBOLSADA, AffectedBooking.DecididoPor.SISTEMA, null, AHORA);
        service.reembolsar(booking.getId(), AffectedBooking.DecididoPor.PASAJERO);
        verify(tickets, never()).anular(any());

        AffectedBooking aceptada = new AffectedBooking(booking.getId(), booking.getItineraryId(),
                AffectedBooking.Causa.RETRASO, AHORA, AHORA);
        aceptada.decidir(AffectedBooking.Estado.ACEPTO_CAMBIO, AffectedBooking.DecididoPor.PASAJERO, null, AHORA);
        when(affected.lockById(booking.getId())).thenReturn(Optional.of(aceptada));
        assertThatThrownBy(() -> service.reembolsar(booking.getId(), AffectedBooking.DecididoPor.PASAJERO))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ALREADY_DECIDED"));
    }

    @Test
    void siElTiqueteYaSeUsoONoExisteNoSeReembolsa() {
        when(tickets.anular(ticket.getId())).thenReturn(0);
        assertThatThrownBy(() -> service.reembolsar(booking.getId(), AffectedBooking.DecididoPor.SISTEMA))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("BOOKING_NOT_REFUNDABLE"));

        when(tickets.findByBookingId(booking.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reembolsar(booking.getId(), AffectedBooking.DecididoPor.SISTEMA))
                .isInstanceOf(ApiException.class);

        booking.cambiarEstado(BookingStatus.REPROGRAMADA, AHORA);
        assertThatThrownBy(() -> service.reembolsar(booking.getId(), AffectedBooking.DecididoPor.SISTEMA))
                .isInstanceOf(ApiException.class);
        verify(gateway, never()).refund(any(), any());
    }

    @Test
    void conElViajeCanceladoNoSeAnulanAsientos() {
        AffectedBooking cancelada = new AffectedBooking(booking.getId(), booking.getItineraryId(),
                AffectedBooking.Causa.CANCELACION, AHORA, AHORA);
        when(affected.lockById(booking.getId())).thenReturn(Optional.of(cancelada));

        service.reembolsar(booking.getId(), AffectedBooking.DecididoPor.SISTEMA);

        assertThat(cancelada.getAnulacionResueltaEn()).isEqualTo(AHORA);
        verify(seatInventory, never()).voidSale(any(), any(), any(), any());
    }

    @Test
    void laAnulacionSeReintentaSiSeatInventoryNoRespondeYQuedaResueltaSiLaRechaza() {
        doThrow(new RemoteErrors.RemoteUnavailableException("caído", null))
                .when(seatInventory).voidSale(any(), any(), any(), any());
        service.anularAsientos(booking.getId());
        assertThat(afectada.getAnulacionResueltaEn()).isNull();
        verify(saga).log(eq(booking.getId()), eq(AffectedRefundService.PASO_ANULACION), eq("REINTENTO"), anyMap());

        doThrow(new ApiException(HttpStatus.CONFLICT, "SEAT_NOT_OWNED", "no"))
                .when(seatInventory).voidSale(any(), any(), any(), any());
        service.anularAsientos(booking.getId());
        assertThat(afectada.getAnulacionResueltaEn()).isEqualTo(AHORA);
        verify(saga).log(eq(booking.getId()), eq(AffectedRefundService.PASO_ANULACION), eq("ERROR"), anyMap());

        // Ya resuelta, o sin reserva: no se vuelve a llamar.
        service.anularAsientos(booking.getId());
        service.anularAsientos(UUID.randomUUID());
        verify(seatInventory, org.mockito.Mockito.times(2)).voidSale(any(), any(), any(), any());
    }

    @Test
    void siOtraPasadaLaResolvioEntreTantoNoSeRegistraDosVeces() {
        AffectedBooking resuelta = new AffectedBooking(booking.getId(), booking.getItineraryId(),
                AffectedBooking.Causa.RETRASO, AHORA, AHORA);
        resuelta.marcarAnulacionResuelta(AHORA.minusSeconds(5));
        when(affected.lockById(booking.getId())).thenReturn(Optional.of(resuelta));

        service.anularAsientos(booking.getId());

        verify(saga, never()).log(any(), eq(AffectedRefundService.PASO_ANULACION), any(), anyMap());
    }
}
