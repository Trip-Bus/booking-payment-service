package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByBookingId(UUID bookingId);

    /**
     * US-033: marca el tiquete como usado solo si sigue EMITIDO. Es un UPDATE condicional atómico:
     * con escaneos concurrentes del mismo QR exactamente uno afecta la fila (devuelve 1) y el resto 0.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Ticket t SET t.estado = 'USADO', t.usadoEn = :ahora, t.usadoPor = :validadorId "
            + "WHERE t.id = :id AND t.estado = 'EMITIDO'")
    int marcarUsado(@Param("id") UUID id, @Param("ahora") Instant ahora, @Param("validadorId") UUID validadorId);
}
