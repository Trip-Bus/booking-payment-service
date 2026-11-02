package com.tripbus.booking.repository;

import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    java.util.Optional<Payment> findByPaymentGatewayId(String paymentGatewayId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") UUID id);

    List<Payment> findByBookingIdOrderByCreadoEnDesc(UUID bookingId);

    @Query("select p.id from Payment p where p.estado = :estado and p.creadoEn < :antes")
    List<UUID> findIdsByEstadoAndCreadoEnBefore(@Param("estado") PaymentStatus estado, @Param("antes") Instant antes);

    /**
     * US-190: ventas a las que les falta la comisión, o cuyo reembolso aún no la revirtió (la comisión llegó
     * después del reembolso). Las más antiguas primero.
     */
    @Query("select p.id from Payment p where p.aprobadoEn is not null and (p.comisionPorcentaje is null"
            + " or exists (select 1 from Refund r where r.paymentId = p.id and r.comisionValor is null))"
            + " order by p.aprobadoEn, p.id")
    List<UUID> findVentasSinComision(Pageable pageable);

    /** US-190: guarda la comisión solo si aún no la tiene (dos procesos que la calculan a la vez no la pisan). */
    @Modifying
    @Query("update Payment p set p.comisionPorcentaje = :porcentaje, p.comisionValor = :valor"
            + " where p.id = :id and p.comisionPorcentaje is null")
    int registrarComision(@Param("id") UUID id, @Param("porcentaje") BigDecimal porcentaje,
                          @Param("valor") BigDecimal valor);

    /** US-190: el reembolso de la venta registra su comisión en negativo (si la venta ya la tiene). */
    @Modifying
    @Query(value = "UPDATE refunds r SET comision_valor = -p.comision_valor FROM payments p"
            + " WHERE p.id = r.payment_id AND r.payment_id = :id AND r.comision_valor IS NULL"
            + " AND p.comision_valor IS NOT NULL", nativeQuery = true)
    int revertirComisionDelReembolso(@Param("id") UUID paymentId);
}
