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
     * US-033: marca el tiquete como usado solo si sigue EMITIDO y el QR escaneado es el vigente (US-139: un
     * cambio de asiento reemplaza el qr_token). Es un UPDATE condicional atómico: con escaneos concurrentes del
     * mismo QR exactamente uno afecta la fila (devuelve 1) y el resto 0.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Ticket t SET t.estado = 'USADO', t.usadoEn = :ahora, t.usadoPor = :validadorId "
            + "WHERE t.id = :id AND t.qrToken = :qrToken AND t.estado = 'EMITIDO'")
    int marcarUsado(@Param("id") UUID id, @Param("qrToken") String qrToken, @Param("ahora") Instant ahora,
                    @Param("validadorId") UUID validadorId);

    /**
     * US-150: el tiquete pasa a REPROGRAMADO solo si sigue EMITIDO. Compite con {@link #marcarUsado} sobre la
     * misma fila: o se aborda con el tiquete viejo o se reprograma, nunca las dos cosas.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Ticket t SET t.estado = 'REPROGRAMADO' WHERE t.id = :id AND t.estado = 'EMITIDO'")
    int marcarReprogramado(@Param("id") UUID id);

    /**
     * US-139: QR nuevo para un tiquete EMITIDO (el anterior deja de validar). Se reinicia la notificación para
     * que el tiquete se vuelva a enviar por correo.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Ticket t SET t.qrCode = :qrCode, t.qrToken = :qrToken, t.notificadoEn = null, "
            + "t.intentosNotificacion = 0 WHERE t.id = :id AND t.estado = 'EMITIDO'")
    int reemplazarQr(@Param("id") UUID id, @Param("qrCode") String qrCode, @Param("qrToken") String qrToken);

    /**
     * US-154/US-155: el tiquete de una reserva afectada que se reembolsa pasa a ANULADO, solo si sigue EMITIDO
     * (compite con el abordaje sobre la misma fila).
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Ticket t SET t.estado = 'ANULADO' WHERE t.id = :id AND t.estado = 'EMITIDO'")
    int anular(@Param("id") UUID id);

    /**
     * US-172: pasajeros del viaje con tiquete EMITIDO (pendiente de abordar) o USADO (abordó). Los REPROGRAMADO y
     * ANULADO no van en el manifiesto.
     */
    @Query("select new com.tripbus.booking.repository.ManifestRow(p.nombre, p.tipoDocumento, p.numeroDocumento, "
            + "p.asiento, b.id, t.estado, t.usadoEn) from Passenger p, Booking b, Ticket t "
            + "where p.bookingId = b.id and t.bookingId = b.id and b.itineraryId = :itineraryId "
            + "and t.estado in ('EMITIDO', 'USADO')")
    List<ManifestRow> findManifest(@Param("itineraryId") UUID itineraryId);

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
     * reinicio. Solo los EMITIDO: un tiquete que ya se reprogramó o se anuló no se envía.
     */
    @Query("select t.id from Ticket t, Booking b where b.id = t.bookingId and b.emailContacto is not null "
            + "and t.estado = 'EMITIDO' and t.notificadoEn is null and t.intentosNotificacion < :maxIntentos "
            + "and (t.intentosNotificacion > 0 or t.emitidoEn < :emitidosAntes) order by t.emitidoEn")
    List<UUID> findIdsPendientesDeNotificar(@Param("maxIntentos") int maxIntentos,
                                            @Param("emitidosAntes") Instant emitidosAntes);
}
