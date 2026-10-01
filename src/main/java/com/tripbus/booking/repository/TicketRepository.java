package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByBookingId(UUID bookingId);
}
