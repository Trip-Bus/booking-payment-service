package com.tripbus.booking.web;

import com.tripbus.booking.service.BookingViewer;
import com.tripbus.booking.service.ReceiptService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** US-146: comprobantes en PDF (dueño de la reserva, OPERADOR de su empresa o ADMIN; los demás reciben 404). */
@RestController
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @GetMapping("/payments/{id}/receipt.pdf")
    public ResponseEntity<byte[]> pago(@PathVariable UUID id, Authentication authentication) {
        return pdf("comprobante-pago-" + id + ".pdf", receiptService.pago(id, BookingViewer.of(authentication)));
    }

    @GetMapping("/refunds/{id}/receipt.pdf")
    public ResponseEntity<byte[]> reembolso(@PathVariable UUID id, Authentication authentication) {
        return pdf("comprobante-reembolso-" + id + ".pdf", receiptService.reembolso(id, BookingViewer.of(authentication)));
    }

    private static ResponseEntity<byte[]> pdf(String archivo, byte[] contenido) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + archivo + "\"")
                .body(contenido);
    }
}
