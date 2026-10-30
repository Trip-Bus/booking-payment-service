package com.tripbus.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.payment.WebhookSigner;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.RefundRepository;
import com.tripbus.booking.repository.WebhookLogRepository;
import com.tripbus.booking.web.dto.PayRequest;
import com.tripbus.booking.web.dto.PaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** US-143: pago con tarjeta, PSE o Nequi (validación de datos, retención y llamada a la pasarela), con mocks. */
class PagoPorMetodoTest {

    private static final Instant AHORA = Instant.parse("2026-10-29T12:00:00Z");

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID holdId = UUID.randomUUID();
    private PaymentService service;
    private final AtomicReference<Payment> guardado = new AtomicReference<>();
    private Booking booking;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        service = new PaymentService(bookings, payments, mock(RefundRepository.class),
                mock(WebhookLogRepository.class), seatInventory, gateway, mock(WebhookSigner.class),
                mock(TicketService.class), mock(TicketMailService.class), mock(RescheduleSaga.class),
                mock(SagaLogger.class), mock(EventPublisher.class), tx, new ObjectMapper(),
                Clock.fixed(AHORA, ZoneOffset.UTC));
        booking = new Booking(holdId, userId, UUID.randomUUID(), null, List.of("12"), new BigDecimal("95000"), "COP",
                AHORA.plusSeconds(600), AHORA);
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookings.lockById(booking.getId())).thenReturn(Optional.of(booking));
        when(seatInventory.getHold(holdId)).thenReturn(new SeatInventoryClient.Hold(holdId, null, List.of("12"),
                userId, null, null, "ACTIVA", AHORA.plusSeconds(600), 600));
        when(payments.save(any(Payment.class))).thenAnswer(inv -> {
            guardado.set(inv.getArgument(0));
            return guardado.get();
        });
        when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
        doAnswer(inv -> {
            inv.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any(Consumer.class));
        when(gateway.charge(any(), any(), anyString(), anyString())).thenReturn("sbx_tarjeta");
        when(gateway.charge(any(), any(), anyString(), any(PaymentGateway.ChargeRequest.class)))
                .thenAnswer(inv -> {
                    PaymentGateway.ChargeRequest r = inv.getArgument(3);
                    return new PaymentGateway.ChargeResult("sbx_" + r.method().toLowerCase(),
                            "PSE".equals(r.method()) ? "/sandbox/pse/sbx_pse" : null);
                });
    }

    private static PayRequest tarjeta(String token, String last4) {
        return new PayRequest(null, token, last4, "VISA", null, null);
    }

    private static PayRequest pse(String banco, String tipoPersona, String documento) {
        return new PayRequest("pse", null, null, null,
                new PayRequest.PseData(banco, tipoPersona, "CC", documento), null);
    }

    private static PayRequest nequi(String telefono) {
        return new PayRequest("Nequi", null, null, null, null, new PayRequest.NequiData(telefono));
    }

    private Payment pagoGuardado() {
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void tarjetaUsaLaInterfazDeCompatibilidadYNoExtiendeLaRetencion() {
        PaymentResponse r = service.pay(booking.getId(), userId, tarjeta("tok_visa_approved", "4242"), "k-1");

        assertThat(r.redirectUrl()).isNull();
        assertThat(r.mensaje()).startsWith("Pago en proceso");
        Payment p = pagoGuardado();
        assertThat(p.getMetodo()).isEqualTo("TARJETA");
        assertThat(p.getMedioPagoUltimos4()).isEqualTo("4242");
        assertThat(p.getBanco()).isNull();
        verify(gateway).charge(any(), any(), eq("COP"), eq("tok_visa_approved"));
        verify(seatInventory, never()).extendHold(any(), anyLong());
    }

    @Test
    void tarjetaSinUltimos4NoGuardaMedioDePago() {
        service.pay(booking.getId(), userId, tarjeta("tok_visa_approved", null), "k-2");

        assertThat(pagoGuardado().getMedioPagoUltimos4()).isNull();
    }

    @Test
    void pseDevuelveLaRedireccionAlBancoYGuardaElBanco() {
        PaymentResponse r = service.pay(booking.getId(), userId, pse("1", "NATURAL", "1020304050"), "k-3");

        assertThat(r.redirectUrl()).isEqualTo("/sandbox/pse/sbx_pse");
        Payment p = pagoGuardado();
        assertThat(p.getMetodo()).isEqualTo("PSE");
        assertThat(p.getBanco()).isEqualTo("Banco que aprueba");
        assertThat(p.getMedioPagoUltimos4()).isEqualTo("4050");
        assertThat(p.getPaymentGatewayId()).isNull(); // lockById no encuentra el pago: lo asigna el webhook
        verify(seatInventory).extendHold(holdId, 300);
        ArgumentCaptor<PaymentGateway.ChargeRequest> req = ArgumentCaptor.forClass(PaymentGateway.ChargeRequest.class);
        verify(gateway).charge(any(), any(), eq("COP"), req.capture());
        assertThat(req.getValue().bankCode()).isEqualTo("1");
        assertThat(req.getValue().phone()).isNull();
    }

    @Test
    void pseConElBancoQueRechazaYPersonaJuridica() {
        service.pay(booking.getId(), userId, pse("2", "JURIDICA", "9001"), "k-4");

        assertThat(pagoGuardado().getBanco()).isEqualTo("Banco que rechaza");
    }

    @Test
    void nequiPideAceptarEnLaAppYSiNoSePuedeExtenderLaRetencionSigue() {
        when(seatInventory.extendHold(holdId, 300)).thenThrow(new IllegalStateException("sin extensión"));
        when(payments.lockById(any())).thenAnswer(inv -> Optional.ofNullable(guardado.get()));

        PaymentResponse r = service.pay(booking.getId(), userId, nequi("3991111111"), "k-5");

        assertThat(r.mensaje()).isEqualTo("Acepta el pago en tu app Nequi");
        assertThat(r.redirectUrl()).isNull();
        Payment p = pagoGuardado();
        assertThat(p.getMetodo()).isEqualTo("NEQUI");
        assertThat(p.getMedioPagoUltimos4()).isEqualTo("1111");
        assertThat(p.getPaymentGatewayId()).isEqualTo("sbx_nequi");
        ArgumentCaptor<PaymentGateway.ChargeRequest> req = ArgumentCaptor.forClass(PaymentGateway.ChargeRequest.class);
        verify(gateway).charge(any(), any(), eq("COP"), req.capture());
        assertThat(req.getValue().phone()).isEqualTo("3991111111");
        assertThat(req.getValue().bankCode()).isNull();
    }

    static Stream<Arguments> datosInvalidos() {
        return Stream.of(
                Arguments.of(new PayRequest("BITCOIN", "tok", null, null, null, null), "INVALID_PAYMENT_METHOD"),
                Arguments.of(tarjeta(null, null), "INVALID_CARD"),
                Arguments.of(tarjeta("  ", null), "INVALID_CARD"),
                Arguments.of(new PayRequest("NEQUI", null, null, null, null, null), "INVALID_NEQUI_PHONE"),
                Arguments.of(nequi(null), "INVALID_NEQUI_PHONE"),
                Arguments.of(nequi("4991111111"), "INVALID_NEQUI_PHONE"),
                Arguments.of(nequi("399111111"), "INVALID_NEQUI_PHONE"),
                Arguments.of(new PayRequest("PSE", null, null, null, null, null), "INVALID_PSE"),
                Arguments.of(pse("9", "NATURAL", "1020304050"), "INVALID_PSE"),
                Arguments.of(pse("1", "OTRA", "1020304050"), "INVALID_PSE"),
                Arguments.of(pse("1", "NATURAL", null), "INVALID_PSE"),
                Arguments.of(pse("1", "NATURAL", "123"), "INVALID_PSE"));
    }

    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("datosInvalidos")
    void datosDePagoInvalidosSeRechazanAntesDeTocarLaReserva(PayRequest request, String codigo) {
        UUID bookingId = booking.getId();

        assertThatThrownBy(() -> service.pay(bookingId, userId, request, "k-x"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(codigo));
        verifyNoInteractions(gateway, seatInventory);
        verify(payments, never()).save(any());
    }
}
