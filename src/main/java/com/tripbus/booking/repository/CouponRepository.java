package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    /** El código es único sin distinguir mayúsculas (índice uq_coupons_codigo sobre lower(codigo)). */
    @Query("select c from Coupon c where lower(c.codigo) = lower(:codigo)")
    Optional<Coupon> findByCodigo(@Param("codigo") String codigo);

    List<Coupon> findAllByOrderByCreadoEnDesc();

    List<Coupon> findByCompanyIdOrderByCreadoEnDesc(UUID companyId);

    /**
     * US-147: cuenta un uso de forma atómica. Devuelve 0 si ya no quedan usos: con muchas compras simultáneas, la fila
     * bloqueada por el UPDATE hace que solo las primeras {@code usos_maximos} lo logren.
     */
    @Modifying
    @Query(value = "UPDATE coupons SET usos = usos + 1, actualizado_en = :ahora"
            + " WHERE id = :id AND (usos_maximos IS NULL OR usos < usos_maximos)", nativeQuery = true)
    int sumarUso(@Param("id") UUID id, @Param("ahora") Instant ahora);

    /** US-147: devuelve un uso (la reserva venció o el pago se rechazó). */
    @Modifying
    @Query(value = "UPDATE coupons SET usos = usos - 1, actualizado_en = :ahora WHERE id = :id AND usos > 0",
            nativeQuery = true)
    int restarUso(@Param("id") UUID id, @Param("ahora") Instant ahora);

    /** Usos actuales (el contador lo cambian solo las consultas atómicas: no se lee del caché de la sesión). */
    @Query(value = "SELECT usos FROM coupons WHERE id = :id", nativeQuery = true)
    int usos(@Param("id") UUID id);
}
