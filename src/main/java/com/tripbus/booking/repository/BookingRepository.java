package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /** US-145: mis viajes con salida desde {@code desde} (próximos), de la más cercana a la más lejana. */
    @Query(value = "select b from Booking b where b.userId = :userId and b.estado in :estados "
            + "and b.fechaSalida >= :desde order by b.fechaSalida asc, b.creadoEn asc",
            countQuery = "select count(b) from Booking b where b.userId = :userId and b.estado in :estados "
                    + "and b.fechaSalida >= :desde")
    Page<Booking> findProximos(@Param("userId") UUID userId, @Param("estados") Collection<BookingStatus> estados,
                               @Param("desde") Instant desde, Pageable pageable);

    /** US-145: el resto (salida antes de {@code desde}, o aún sin datos del viaje), de la más reciente a la más vieja. */
    @Query(value = "select b from Booking b where b.userId = :userId and b.estado in :estados "
            + "and (b.fechaSalida < :desde or b.fechaSalida is null) order by b.fechaSalida desc nulls last, "
            + "b.creadoEn desc",
            countQuery = "select count(b) from Booking b where b.userId = :userId and b.estado in :estados "
                    + "and (b.fechaSalida < :desde or b.fechaSalida is null)")
    Page<Booking> findPasados(@Param("userId") UUID userId, @Param("estados") Collection<BookingStatus> estados,
                              @Param("desde") Instant desde, Pageable pageable);

    /** Viajes con reservas que aún no tienen sus datos (los rellena TripDataBackfill). */
    @Query("select distinct b.itineraryId from Booking b where b.fechaSalida is null")
    List<UUID> findItinerariosSinViaje();

    /** TripDataBackfill: copia los datos del viaje en sus reservas que aún no los tienen (idempotente). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Booking b set b.origen = :origen, b.destino = :destino, b.fechaSalida = :salida, "
            + "b.empresaNombre = :empresa where b.itineraryId = :itineraryId and b.fechaSalida is null")
    int rellenarViaje(@Param("itineraryId") UUID itineraryId, @Param("origen") String origen,
                      @Param("destino") String destino, @Param("salida") Instant salida,
                      @Param("empresa") String empresa);

    /** ItineraryRescheduled: la nueva salida del viaje en todas sus reservas. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Booking b set b.fechaSalida = :salida where b.itineraryId = :itineraryId")
    int actualizarSalida(@Param("itineraryId") UUID itineraryId, @Param("salida") Instant salida);

    /** US-144: ventas en taquilla creadas en [desde, hasta), en orden de venta. */
    @Query("select b from Booking b where b.canal = com.tripbus.booking.domain.Booking.Canal.TAQUILLA "
            + "and b.creadoEn >= :desde and b.creadoEn < :hasta order by b.creadoEn")
    List<Booking> findVentasTaquilla(@Param("desde") Instant desde, @Param("hasta") Instant hasta);

    /** US-172: empresa del viaje según sus reservas (si catalog no responde). */
    @Query("select distinct b.companyId from Booking b where b.itineraryId = :itineraryId and b.companyId is not null")
    List<UUID> findCompanyIdsByItineraryId(@Param("itineraryId") UUID itineraryId);
}
