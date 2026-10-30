package com.tripbus.booking.web;

import com.tripbus.booking.config.PaymentProperties;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.payment.SandboxPaymentGateway;
import com.tripbus.booking.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-143: banco de pruebas PSE (página y decisión), con el repositorio y la pasarela simulados. */
class SandboxPaymentControllerTest {

    private static final String GATEWAY_ID = "sbx_0123456789abcdef0123";
    private static final Instant AHORA = Instant.parse("2026-10-29T12:00:00Z");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final SandboxPaymentGateway sandbox = mock(SandboxPaymentGateway.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SandboxPaymentGateway> provider = mock(ObjectProvider.class);
    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        when(provider.getIfAvailable()).thenReturn(sandbox);
    }

    private static PaymentProperties propiedades(boolean paginas) {
        return new PaymentProperties("http://localhost/webhooks/payment", "s", Duration.ZERO, false, null, null,
                paginas, "https://tripbus.test");
    }

    private SandboxPaymentController controlador(boolean paginas) {
        return new SandboxPaymentController(provider, payments, propiedades(paginas));
    }

    private Payment pagoPse(String banco) {
        Payment p = new Payment(bookingId, new BigDecimal("95000"), "k", "PSE", "5050", null, banco, AHORA);
        when(payments.findByPaymentGatewayId(GATEWAY_ID)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void listaLosBancosDePrueba() {
        assertThat(controlador(true).banks()).extracting(b -> b.get("codigo")).containsExactly("1", "2");
    }

    @Test
    void laPaginaDelBancoQueApruebaOfreceAprobarYRechazar() {
        pagoPse("Banco que aprueba");

        ResponseEntity<String> page = controlador(true).page(GATEWAY_ID);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("COP 95000", bookingId.toString(), GATEWAY_ID,
                "value='approve'", "value='reject'");
    }

    @Test
    void laPaginaDelBancoQueRechazaSoloPermiteRechazar() {
        pagoPse("Banco que rechaza");

        assertThat(controlador(true).page(GATEWAY_ID).getBody()).doesNotContain("approve").contains("value='reject'");
    }

    @Test
    void laPaginaEscapaElIdDelCobro() {
        String malicioso = "<script>alert(1)</script>";
        Payment p = new Payment(bookingId, BigDecimal.TEN, "k", "PSE", "5050", null, "Banco que aprueba", AHORA);
        when(payments.findByPaymentGatewayId(malicioso)).thenReturn(Optional.of(p));

        assertThat(controlador(true).page(malicioso).getBody()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void sinPaginasSandboxOSinPagoLaPaginaNoExiste() {
        pagoPse("Banco que aprueba");
        when(payments.findByPaymentGatewayId("sbx_otro")).thenReturn(Optional.empty());

        assertThat(controlador(false).page(GATEWAY_ID).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controlador(true).page("sbx_otro").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aprobarCompletaLaPseYRedirigeAlFrontend() {
        pagoPse("Banco que aprueba");

        ResponseEntity<Void> r = controlador(true).decide(GATEWAY_ID, "approve");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(r.getHeaders().getLocation()).isEqualTo(URI.create("https://tripbus.test/pago/" + bookingId));
        verify(sandbox).completePse(GATEWAY_ID, true);
    }

    @Test
    void elBancoQueRechazaNuncaApruebaAunqueSeLoPidan() {
        pagoPse("Banco que rechaza");

        controlador(true).decide(GATEWAY_ID, "approve");

        verify(sandbox).completePse(GATEWAY_ID, false);
    }

    @Test
    void rechazarCompletaLaPseComoRechazada() {
        pagoPse("Banco que aprueba");

        assertThat(controlador(true).decide(GATEWAY_ID, "reject").getStatusCode()).isEqualTo(HttpStatus.FOUND);
        verify(sandbox).completePse(GATEWAY_ID, false);
    }

    @Test
    void decisionesInvalidasNoTocanLaPasarela() {
        pagoPse("Banco que aprueba");
        when(payments.findByPaymentGatewayId("sbx_otro")).thenReturn(Optional.empty());

        assertThat(controlador(false).decide(GATEWAY_ID, "approve").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controlador(true).decide(GATEWAY_ID, "borrar").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controlador(true).decide("sbx_otro", "approve").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(provider.getIfAvailable()).thenReturn(null);
        assertThat(controlador(true).decide(GATEWAY_ID, "approve").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(sandbox, never()).completePse(anyString(), anyBoolean());
    }
}
