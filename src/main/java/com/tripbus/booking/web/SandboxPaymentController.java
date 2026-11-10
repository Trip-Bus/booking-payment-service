package com.tripbus.booking.web;

import com.tripbus.booking.config.PaymentProperties;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.payment.SandboxPaymentGateway;
import com.tripbus.booking.repository.PaymentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Páginas públicas únicamente para el sandbox local; los ids de pasarela son aleatorios. */
@RestController
public class SandboxPaymentController {
    // Opcional: con otra pasarela (o un mock de PaymentGateway en pruebas) no hay sandbox que completar.
    private final ObjectProvider<SandboxPaymentGateway> gateway;
    private final PaymentRepository payments;
    private final PaymentProperties properties;
    public SandboxPaymentController(ObjectProvider<SandboxPaymentGateway> gateway, PaymentRepository payments, PaymentProperties properties) {
        this.gateway = gateway; this.payments = payments; this.properties = properties;
    }
    @GetMapping("/payments/pse/banks")
    public List<Map<String, String>> banks() {
        return List.of(Map.of("codigo", "1", "nombre", "Banco que aprueba"), Map.of("codigo", "2", "nombre", "Banco que rechaza"));
    }
    @GetMapping(value = "/sandbox/pse/{gatewayPaymentId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String gatewayPaymentId) {
        if (!properties.sandboxPagesEnabled()) return ResponseEntity.notFound().build();
        Payment payment = payments.findByPaymentGatewayId(gatewayPaymentId).orElse(null);
        if (payment == null) return ResponseEntity.notFound().build();
        boolean onlyReject = "Banco que rechaza".equals(payment.getBanco());
        String id = HtmlUtils.htmlEscape(gatewayPaymentId);
        String amount = HtmlUtils.htmlEscape(payment.getMonto().toPlainString());
        String booking = HtmlUtils.htmlEscape(payment.getBookingId().toString());
        String approve = onlyReject ? "" : "<button name='action' value='approve'>Aprobar pago</button>";
        String html = "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font:16px sans-serif;max-width:36rem;margin:3rem auto;padding:1rem}button{padding:.8rem;margin:.4rem}</style><h1>Banco de prueba</h1><p>Valor: COP " + amount + "</p><p>Reserva: " + booking + "</p><form method='post'>" + approve + "<button name='action' value='reject'>Rechazar pago</button></form><small>" + id + "</small>";
        return ResponseEntity.ok(html);
    }
    @PostMapping("/sandbox/pse/{gatewayPaymentId}")
    public ResponseEntity<Void> decide(@PathVariable String gatewayPaymentId, @RequestParam String action) {
        if (!properties.sandboxPagesEnabled()) return ResponseEntity.notFound().build();
        if (!"approve".equals(action) && !"reject".equals(action)) return ResponseEntity.badRequest().build();
        Payment payment = payments.findByPaymentGatewayId(gatewayPaymentId).orElse(null);
        if (payment == null) return ResponseEntity.notFound().build();
        SandboxPaymentGateway sandbox = gateway.getIfAvailable();
        if (sandbox == null) return ResponseEntity.notFound().build();
        boolean approve = "approve".equals(action) && !"Banco que rechaza".equals(payment.getBanco());
        sandbox.completePse(gatewayPaymentId, approve);
        return ResponseEntity.status(302).location(URI.create(properties.frontendUrl() + "/pago/" + payment.getBookingId())).build();
    }
}
