package com.tripbus.booking.web.dto;

import java.util.UUID;

/**
 * US-190: facturación de TripBus a una empresa en el mes (COP enteros). cuotaMensual y totalACobrar son null si
 * catalog no respondió (no se conoce la cuota vigente).
 */
public record PlatformBillingRow(
        UUID companyId,
        String empresa,
        Long cuotaMensual,
        long comision,
        Long totalACobrar,
        long tiquetes) {
}
