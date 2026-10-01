package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByHoldId(UUID holdId);

    List<Booking> findByUserIdOrderByCreadoEnDesc(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockById(@Param("id") UUID id);

    @Query("select b.id from Booking b where b.estado = :estado and b.actualizadoEn < :antes order by b.actualizadoEn")
    List<UUID> findIdsByEstadoAndActualizadoEnBefore(@Param("estado") BookingStatus estado,
                                                     @Param("antes") Instant antes);

    long countByEstado(BookingStatus estado);
}
