package com.tripbus.booking.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-154: reserva afectada por un cambio de la empresa. opciones: ACEPTAR_CAMBIO, REPROGRAMAR y/o REEMBOLSO según la
 * causa. bookingNuevoId: la reserva a la que se reprogramó o reubicó (null si no aplica).
 */
public record AffectedBookingResponse(
        UUID bookingId,
        UUID ticketId,
        UUID itineraryId,
        String causa,
        String estado,
        Instant plazoDecision,
        List<String> opciones,
        Instant decididoEn,
        String decididoPor,
        UUID bookingNuevoId) {
}
