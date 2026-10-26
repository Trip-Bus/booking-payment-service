package com.tripbus.booking.web;

import com.tripbus.booking.common.security.CurrentUser;
import com.tripbus.booking.service.BookingViewer;
import com.tripbus.booking.service.TicketChangeService;
import com.tripbus.booking.service.TicketService;
import com.tripbus.booking.web.dto.ChangeSeatRequest;
import com.tripbus.booking.web.dto.PassengerRequest;
import com.tripbus.booking.web.dto.TicketResponse;
import com.tripbus.booking.web.dto.ValidateTicketRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final TicketChangeService ticketChangeService;

    public TicketController(TicketService ticketService, TicketChangeService ticketChangeService) {
        this.ticketService = ticketService;
        this.ticketChangeService = ticketChangeService;
    }

    /** US-033: tiquete electrónico con QR (qrImage = data URI PNG). */
    @GetMapping("/{id}")
    public TicketResponse get(@PathVariable UUID id, Authentication authentication) {
        return ticketService.get(id, CurrentUser.id(authentication), isStaff(authentication));
    }

    @GetMapping(value = "/{id}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(ticketService.qrImage(id, CurrentUser.id(authentication), isStaff(authentication)));
    }

    /** Validación del QR al abordar (conductor u operador): el QR solo se puede usar una vez. */
    @PostMapping("/validate")
    @PreAuthorize("hasAnyRole('CONDUCTOR', 'OPERADOR', 'ADMIN')")
    public Map<String, Object> validate(@Valid @RequestBody ValidateTicketRequest request,
                                        Authentication authentication) {
        return ticketService.validate(request.qrCode(), CurrentUser.id(authentication));
    }

    /** US-139: el dueño cambia de asiento en el mismo viaje (hasta 30 min antes); el tiquete queda con QR nuevo. */
    @PostMapping("/{id}/seat")
    public TicketResponse changeSeat(@PathVariable UUID id, @Valid @RequestBody ChangeSeatRequest request,
                                     Authentication authentication) {
        return ticketChangeService.changeSeat(id, BookingViewer.of(authentication), request);
    }

    /** US-152: el dueño corrige el nombre o el documento de un pasajero (una vez, hasta 1 h antes). */
    @PatchMapping("/{id}/passengers/{passengerId}")
    public TicketResponse correctPassenger(@PathVariable UUID id, @PathVariable UUID passengerId,
                                           @Valid @RequestBody PassengerRequest request,
                                           Authentication authentication) {
        return ticketChangeService.correctPassenger(id, passengerId, BookingViewer.of(authentication), request);
    }

    private static boolean isStaff(Authentication authentication) {
        return CurrentUser.hasRole(authentication, "ADMIN") || CurrentUser.hasRole(authentication, "OPERADOR");
    }
}
