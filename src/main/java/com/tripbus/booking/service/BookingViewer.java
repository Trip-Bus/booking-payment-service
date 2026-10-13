package com.tripbus.booking.service;

import com.tripbus.booking.common.security.CurrentUser;
import com.tripbus.booking.domain.Booking;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * Quién consulta una reserva (US-005): el dueño ve las suyas, ADMIN ve todas y OPERADOR solo las de
 * su empresa (claim companyId del JWT). Las reservas sin company_id (creadas antes de guardarlo) solo
 * las ven su dueño y ADMIN.
 */
public record BookingViewer(UUID userId, boolean admin, boolean operador, UUID companyId) {

    public static BookingViewer of(Authentication authentication) {
        return new BookingViewer(CurrentUser.id(authentication),
                CurrentUser.hasRole(authentication, "ADMIN"),
                CurrentUser.hasRole(authentication, "OPERADOR"),
                CurrentUser.companyId(authentication));
    }

    public boolean canSee(Booking booking) {
        if (admin || booking.getUserId().equals(userId)) {
            return true;
        }
        return operador && companyId != null && companyId.equals(booking.getCompanyId());
    }
}
