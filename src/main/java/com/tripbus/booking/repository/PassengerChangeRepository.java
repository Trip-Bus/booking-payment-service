package com.tripbus.booking.repository;

import com.tripbus.booking.domain.PassengerChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PassengerChangeRepository extends JpaRepository<PassengerChange, UUID> {

    boolean existsByPassengerId(UUID passengerId);
}
