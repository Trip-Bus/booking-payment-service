package com.tripbus.booking.repository;

import com.tripbus.booking.domain.CouponRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

    long countByCouponIdAndUserIdAndEstado(UUID couponId, UUID userId, String estado);

    Optional<CouponRedemption> findByBookingIdAndEstado(UUID bookingId, String estado);

    /** Libera el uso activo de la reserva (si lo hay). Devuelve 1 si lo liberó. */
    @Modifying
    @Query("update CouponRedemption r set r.estado = 'LIBERADO', r.liberadoEn = :ahora"
            + " where r.bookingId = :bookingId and r.estado = 'ACTIVO'")
    int liberar(@Param("bookingId") UUID bookingId, @Param("ahora") Instant ahora);
}
