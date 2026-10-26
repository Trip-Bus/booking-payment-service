package com.tripbus.booking.web;

import com.tripbus.booking.common.security.CurrentUser;
import com.tripbus.booking.service.BookingService;
import com.tripbus.booking.service.BookingViewer;
import com.tripbus.booking.service.IdempotencyService;
import com.tripbus.booking.service.PaymentService;
import com.tripbus.booking.service.RescheduleService;
import com.tripbus.booking.web.dto.BookingResponse;
import com.tripbus.booking.web.dto.CreateBookingRequest;
import com.tripbus.booking.web.dto.PayRequest;
import com.tripbus.booking.web.dto.RescheduleRequest;
import com.tripbus.booking.web.dto.SagaStepResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    private final RescheduleService rescheduleService;

    public BookingController(BookingService bookingService, PaymentService paymentService,
                             IdempotencyService idempotencyService, RescheduleService rescheduleService) {
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
        this.rescheduleService = rescheduleService;
    }

    /** US-031: crea la reserva con los datos del viajero y devuelve el total a pagar. */
    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request,
                                                  Authentication authentication) {
        BookingService.CreateResult result = bookingService.create(request, CurrentUser.id(authentication),
                CurrentUser.email(authentication));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.booking());
    }

    @GetMapping
    public List<BookingResponse> mine(Authentication authentication) {
        return bookingService.mine(CurrentUser.id(authentication));
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable UUID id, Authentication authentication) {
        return bookingService.get(id, BookingViewer.of(authentication));
    }

    /** Bitácora de la saga (útil para la demo y para soporte). */
    @GetMapping("/{id}/saga")
    public List<SagaStepResponse> saga(@PathVariable UUID id, Authentication authentication) {
        return bookingService.sagaLog(id, BookingViewer.of(authentication));
    }

    /**
     * US-150: reprograma la reserva a otro viaje con los asientos de la retención {holdId}. 201 con la reserva
     * nueva (PENDIENTE_PAGO si hay diferencia, CONFIRMADA si no); 200 si esa retención ya se usó para esto.
     */
    @PostMapping("/{id}/reschedule")
    public ResponseEntity<BookingResponse> reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest request,
                                                      Authentication authentication) {
        BookingService.CreateResult result = rescheduleService.reschedule(id, request.holdId(),
                BookingViewer.of(authentication), CurrentUser.email(authentication));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.booking());
    }

    /** US-032 + US-034: pago con tarjeta. Requiere el header Idempotency-Key. */
    @PostMapping("/{id}/pay")
    public ResponseEntity<Object> pay(@PathVariable UUID id,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                      @Valid @RequestBody PayRequest request,
                                      Authentication authentication) {
        UUID userId = CurrentUser.id(authentication);
        String requestHash = IdempotencyService.hash(id.toString(), request.cardToken(), request.cardLast4(),
                request.cardBrand());
        return idempotencyService.execute(userId, idempotencyKey, requestHash, () ->
                ResponseEntity.status(HttpStatus.ACCEPTED).body(paymentService.pay(id, userId, request, idempotencyKey)));
    }
}
