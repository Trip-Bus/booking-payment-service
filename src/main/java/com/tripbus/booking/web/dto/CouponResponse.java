package com.tripbus.booking.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** US-147: un cupón con sus usos actuales. */
public record CouponResponse(
        UUID couponId,
        String codigo,
        String tipo,
        BigDecimal valor,
        UUID companyId,
        Instant vigenteDesde,
        Instant vigenteHasta,
        Integer usosMaximos,
        int usos,
        Integer usosPorUsuario,
        BigDecimal compraMinima,
        List<UUID> routeIds,
        boolean activo) {
}
