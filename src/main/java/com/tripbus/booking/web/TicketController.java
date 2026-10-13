package com.tripbus.booking.web;

import com.tripbus.booking.common.security.CurrentUser;
import com.tripbus.booking.service.TicketService;
import com.tripbus.booking.web.dto.TicketResponse;
import com.tripbus.booking.web.dto.ValidateTicketRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
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

    private static boolean isStaff(Authentication authentication) {
        return CurrentUser.hasRole(authentication, "ADMIN") || CurrentUser.hasRole(authentication, "OPERADOR");
    }
}
