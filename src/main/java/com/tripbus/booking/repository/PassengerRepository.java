package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Passenger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PassengerRepository extends JpaRepository<Passenger, UUID> {

    List<Passenger> findByBookingId(UUID bookingId);
}
