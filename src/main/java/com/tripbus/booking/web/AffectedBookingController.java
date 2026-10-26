package com.tripbus.booking.web;

import com.tripbus.booking.common.security.CurrentUser;
import com.tripbus.booking.service.AffectedBookingService;
import com.tripbus.booking.service.BookingViewer;
import com.tripbus.booking.web.dto.AffectedBookingResponse;
import com.tripbus.booking.web.dto.RescheduleRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * US-154: reservas afectadas por un cambio de la empresa y la decisión del dueño. El reembolso solo existe aquí: el
 * pasajero no tuvo la culpa.
 */
@RestController
@RequestMapping("/bookings")
public class AffectedBookingController {

    private final AffectedBookingService affectedService;

    public AffectedBookingController(AffectedBookingService affectedService) {
        this.affectedService = affectedService;
    }

    /** Mis reservas afectadas pendientes de decidir. */
    @GetMapping("/affected")
    public List<AffectedBookingResponse> mine(Authentication authentication) {
        return affectedService.mine(CurrentUser.id(authentication));
    }

    @GetMapping("/{id}/affected")
    public AffectedBookingResponse get(@PathVariable UUID id, Authentication authentication) {
        return affectedService.get(id, BookingViewer.of(authentication));
    }

    /** Reprogramar gratis con los asientos de la retención {holdId} (POST /seats/holds en el viaje nuevo). */
    @PostMapping("/{id}/affected/reschedule")
    public AffectedBookingResponse reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest request,
                                              Authentication authentication) {
        return affectedService.reschedule(id, request.holdId(), BookingViewer.of(authentication));
    }

    /** Reembolso del total pagado (una sola vez; repetirlo responde lo mismo). */
    @PostMapping("/{id}/affected/refund")
    public AffectedBookingResponse refund(@PathVariable UUID id, Authentication authentication) {
        return affectedService.refund(id, BookingViewer.of(authentication));
    }

    /** Aceptar el nuevo horario (solo si el viaje se reprogramó o se retrasó). */
    @PostMapping("/{id}/affected/accept")
    public AffectedBookingResponse accept(@PathVariable UUID id, Authentication authentication) {
        return affectedService.accept(id, BookingViewer.of(authentication));
    }
}
