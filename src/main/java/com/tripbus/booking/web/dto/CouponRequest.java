package com.tripbus.booking.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-147: crear o editar un cupón. companyId null = cupón de TripBus (solo ADMIN). usosMaximos y usosPorUsuario null =
 * sin límite; routeIds vacío = cualquier ruta; activo null = true.
 */
public record CouponRequest(
        @NotBlank(message = "El código es obligatorio")
        @Pattern(regexp = "[A-Za-z0-9_-]{3,30}", message = "El código debe tener de 3 a 30 letras, números, - o _")
        String codigo,
        @NotNull(message = "El tipo es obligatorio")
        @Pattern(regexp = "PORCENTAJE|VALOR", message = "El tipo debe ser PORCENTAJE o VALOR")
        String tipo,
        @NotNull(message = "El valor es obligatorio")
        @DecimalMin(value = "0", inclusive = false, message = "El valor debe ser mayor que 0")
        BigDecimal valor,
        UUID companyId,
        @NotNull(message = "vigenteDesde es obligatorio")
        Instant vigenteDesde,
        @NotNull(message = "vigenteHasta es obligatorio")
        Instant vigenteHasta,
        @Min(value = 1, message = "usosMaximos debe ser al menos 1")
        Integer usosMaximos,
        @Min(value = 1, message = "usosPorUsuario debe ser al menos 1")
        Integer usosPorUsuario,
        @DecimalMin(value = "0", message = "La compra mínima no puede ser negativa")
        BigDecimal compraMinima,
        List<UUID> routeIds,
        Boolean activo) {
}
