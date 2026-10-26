package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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

    /** US-150: hay una reprogramación de {@code origenBookingId} en alguno de esos estados. */
    boolean existsByOrigenBookingIdAndEstadoIn(UUID origenBookingId, Collection<BookingStatus> estados);

    /** US-150: reservas REPROGRAMADA cuyos asientos viejos aún no se anularon en seat-inventory. */
    @Query("select b.id from Booking b where b.estado = com.tripbus.booking.domain.BookingStatus.REPROGRAMADA "
            + "and b.anulacionResueltaEn is null and b.actualizadoEn < :antes order by b.actualizadoEn")
    List<UUID> findIdsConAnulacionPendiente(@Param("antes") Instant antes);

    /** US-154: reservas CONFIRMADA del viaje cuyo tiquete sigue EMITIDO (afectadas por un cambio de la empresa). */
    @Query("select b from Booking b, Ticket t where t.bookingId = b.id and b.itineraryId = :itineraryId "
            + "and b.estado = com.tripbus.booking.domain.BookingStatus.CONFIRMADA and t.estado = 'EMITIDO'")
    List<Booking> findConfirmadasConTiqueteEmitido(@Param("itineraryId") UUID itineraryId);

    /** US-155: la reprogramación más reciente de {@code origenBookingId}. */
    Optional<Booking> findFirstByOrigenBookingIdOrderByCreadoEnDesc(UUID origenBookingId);

    /** US-172: empresa del viaje según sus reservas (si catalog no responde). */
    @Query("select distinct b.companyId from Booking b where b.itineraryId = :itineraryId and b.companyId is not null")
    List<UUID> findCompanyIdsByItineraryId(@Param("itineraryId") UUID itineraryId);
}
