package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") UUID id);

    List<Payment> findByBookingIdOrderByCreadoEnDesc(UUID bookingId);

    @Query("select p.id from Payment p where p.estado = :estado and p.creadoEn < :antes")
    List<UUID> findIdsByEstadoAndCreadoEnBefore(@Param("estado") PaymentStatus estado, @Param("antes") Instant antes);
}
