package com.tripbus.booking.repository;

import com.tripbus.booking.domain.AffectedBooking;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AffectedBookingRepository extends JpaRepository<AffectedBooking, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AffectedBooking a where a.bookingId = :id")
    Optional<AffectedBooking> lockById(@Param("id") UUID id);

    /** Pendientes del usuario, la que vence primero arriba. */
    @Query("select a from AffectedBooking a, Booking b where b.id = a.bookingId and b.userId = :userId "
            + "and a.estado = com.tripbus.booking.domain.AffectedBooking.Estado.PENDIENTE order by a.plazoDecision")
    List<AffectedBooking> findPendientesDelUsuario(@Param("userId") UUID userId);

    /** US-155: pendientes cuyo plazo ya venció (las decide el sistema). */
    @Query("select a.bookingId from AffectedBooking a where a.estado = "
            + "com.tripbus.booking.domain.AffectedBooking.Estado.PENDIENTE and a.plazoDecision <= :ahora "
            + "order by a.plazoDecision")
    List<UUID> findIdsConPlazoVencido(@Param("ahora") Instant ahora);

    /** Reembolsos de viajes no cancelados cuyos asientos seat-inventory aún no anuló. */
    @Query("select a.bookingId from AffectedBooking a where a.estado = "
            + "com.tripbus.booking.domain.AffectedBooking.Estado.REEMBOLSADA "
            + "and a.causa <> com.tripbus.booking.domain.AffectedBooking.Causa.CANCELACION "
            + "and a.anulacionResueltaEn is null and a.actualizadoEn < :antes")
    List<UUID> findIdsConAnulacionPendiente(@Param("antes") Instant antes);
}
