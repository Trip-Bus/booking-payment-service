package com.tripbus.booking.web;

import com.tripbus.booking.service.BookingViewer;
import com.tripbus.booking.service.BoxOfficeService;
import com.tripbus.booking.service.IdempotencyService;
import com.tripbus.booking.web.dto.BoxOfficeSaleRequest;
import com.tripbus.booking.web.dto.CashClosingResponse;
import com.tripbus.booking.web.dto.PassengerRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Collectors;

/** US-144: venta en taquilla con pago en efectivo y cierre de caja del día. */
@RestController
@RequestMapping("/box-office/sales")
public class BoxOfficeController {

    private final BoxOfficeService boxOfficeService;
    private final IdempotencyService idempotencyService;

    public BoxOfficeController(BoxOfficeService boxOfficeService, IdempotencyService idempotencyService) {
        this.boxOfficeService = boxOfficeService;
        this.idempotencyService = idempotencyService;
    }

    /** 201 con la venta (tiquete emitido). Requiere Idempotency-Key: repetirla devuelve la misma venta. */
    @PostMapping
    @PreAuthorize("hasRole('OPERADOR')")
    public ResponseEntity<Object> sell(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                       @Valid @RequestBody BoxOfficeSaleRequest request,
                                       Authentication authentication) {
        BookingViewer operador = BookingViewer.of(authentication);
        String requestHash = IdempotencyService.hash("box-office", request.itineraryId().toString(),
                String.join(",", request.seatNumbers()),
                request.pasajeros().stream().map(BoxOfficeController::pasajero).collect(Collectors.joining(";")),
                request.comprador().nombre(), request.comprador().email(), request.comprador().telefono());
        return idempotencyService.execute(operador.userId(), idempotencyKey, requestHash, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(boxOfficeService.sell(request, operador, idempotencyKey)));
    }

    /**
     * Cierre de caja del día (hora de Colombia; hoy por defecto). El OPERADOR ve sus ventas o, con ?vendedor=, las de
     * otro vendedor de su empresa; el ADMIN ve todas o las de ?vendedor=.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
    public CashClosingResponse cashClosing(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) UUID vendedor,
            Authentication authentication) {
        return boxOfficeService.cashClosing(BookingViewer.of(authentication), fecha, vendedor);
    }

    private static String pasajero(PassengerRequest p) {
        return p.nombre() + "|" + p.tipoDocumento() + "|" + p.numeroDocumento();
    }
}
