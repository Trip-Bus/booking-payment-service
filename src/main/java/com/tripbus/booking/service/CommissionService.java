package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PaymentRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * US-190: comisión de TripBus por venta. Cuando un pago queda APROBADO se consulta a catalog la comisión vigente en
 * la fecha de la venta (hora de Colombia) y se guarda el porcentaje y el valor (monto × porcentaje, al peso).
 *
 * <p>Se hace DESPUÉS del commit y en segundo plano: la venta nunca espera ni se frena por catalog. Si catalog no
 * responde (o la reserva aún no tiene empresa) la comisión queda en null y {@link CommissionJob} la completa. Si la
 * venta ya se reembolsó, su reembolso registra la comisión en negativo (reversión).
 */
@Service
public class CommissionService {

    private static final Logger log = LoggerFactory.getLogger(CommissionService.class);

    /** Las fechas de venta y de vigencia de las condiciones comerciales son de Colombia. */
    public static final ZoneId ZONA = ZoneId.of("America/Bogota");

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final CatalogClient catalog;
    private final TransactionTemplate tx;
    private final ThreadPoolTaskExecutor executor;

    public CommissionService(PaymentRepository paymentRepository, BookingRepository bookingRepository,
                             CatalogClient catalog, TransactionTemplate tx) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.catalog = catalog;
        this.tx = tx;
        this.executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("commission-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.initialize();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** Fecha de la venta en Colombia (la vigencia de la comisión se consulta para esa fecha). */
    public static LocalDate fechaDeVenta(Instant aprobadoEn) {
        return aprobadoEn.atZone(ZONA).toLocalDate();
    }

    /** Registra la comisión del pago cuando la transacción en curso confirme (o ya, si no hay transacción). */
    public void registrarDespuesDelCommit(UUID paymentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enSegundoPlano(executor, paymentId);
                }
            });
        } else {
            enSegundoPlano(executor, paymentId);
        }
    }

    void enSegundoPlano(Executor destino, UUID paymentId) {
        try {
            destino.execute(() -> registrar(paymentId));
        } catch (RuntimeException e) {
            // Cola llena o apagándose: CommissionJob la completa en su siguiente pasada.
            log.warn("No se pudo programar la comisión del pago {}: {}", paymentId, e.getMessage());
        }
    }

    /** Devuelve true si la venta quedó con su comisión (y con su reversión, si ya se reembolsó). Nunca lanza. */
    public boolean registrar(UUID paymentId) {
        return registrar(paymentId, new HashMap<>());
    }

    /** Igual, reutilizando las condiciones de catalog ya consultadas (por empresa y fecha) en una misma pasada. */
    boolean registrar(UUID paymentId, Map<String, Optional<CatalogClient.CompanyTerms>> cache) {
        try {
            return registrarOrThrow(paymentId, cache);
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar la comisión del pago {}: {}", paymentId, e.getMessage());
            return false;
        }
    }

    private boolean registrarOrThrow(UUID paymentId, Map<String, Optional<CatalogClient.CompanyTerms>> cache) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || payment.getAprobadoEn() == null) {
            return false;
        }
        if (payment.getComisionPorcentaje() == null) {
            UUID companyId = bookingRepository.findById(payment.getBookingId()).map(Booking::getCompanyId).orElse(null);
            if (companyId == null) {
                return false;
            }
            LocalDate fecha = fechaDeVenta(payment.getAprobadoEn());
            CatalogClient.CompanyTerms terms = cache.computeIfAbsent(companyId + "|" + fecha,
                    k -> Optional.ofNullable(catalog.companyTerms(companyId, fecha))).orElse(null);
            if (terms == null) {
                return false;
            }
            BigDecimal porcentaje = terms.comisionPorcentaje();
            BigDecimal valor = Payment.comision(payment.getMonto(), porcentaje);
            tx.executeWithoutResult(s -> {
                paymentRepository.registrarComision(paymentId, porcentaje, valor);
                paymentRepository.revertirComisionDelReembolso(paymentId);
            });
        } else {
            tx.executeWithoutResult(s -> paymentRepository.revertirComisionDelReembolso(paymentId));
        }
        return true;
    }
}
