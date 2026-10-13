package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

    /** US-033: el tiquete se envió por correo (solo la primera vez cuenta). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Ticket t SET t.notificadoEn = :ahora WHERE t.id = :id AND t.notificadoEn IS NULL")
    int marcarNotificado(@Param("id") UUID id, @Param("ahora") Instant ahora);

    /** US-033: un envío por correo más que falló. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Ticket t SET t.intentosNotificacion = t.intentosNotificacion + 1 WHERE t.id = :id")
    int sumarIntentoNotificacion(@Param("id") UUID id);

    /**
     * US-033: tiquetes con correo de contacto que siguen sin notificarse y tienen menos de {@code maxIntentos}
     * fallos. Los que aún no tienen ningún intento solo entran si se emitieron antes de {@code emitidosAntes}:
     * así el job no compite con el envío inmediato tras el commit, pero recoge los que se perdieron por un
     * reinicio.
     */
    @Query("select t.id from Ticket t, Booking b where b.id = t.bookingId and b.emailContacto is not null "
            + "and t.notificadoEn is null and t.intentosNotificacion < :maxIntentos "
            + "and (t.intentosNotificacion > 0 or t.emitidoEn < :emitidosAntes) order by t.emitidoEn")
    List<UUID> findIdsPendientesDeNotificar(@Param("maxIntentos") int maxIntentos,
                                            @Param("emitidosAntes") Instant emitidosAntes);
}
