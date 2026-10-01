package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByPaymentId(UUID paymentId);
}
