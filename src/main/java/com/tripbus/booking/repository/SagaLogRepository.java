package com.tripbus.booking.repository;

import com.tripbus.booking.domain.SagaLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SagaLogRepository extends JpaRepository<SagaLogEntry, Long> {

    List<SagaLogEntry> findByBookingIdOrderByIdAsc(UUID bookingId);
}
