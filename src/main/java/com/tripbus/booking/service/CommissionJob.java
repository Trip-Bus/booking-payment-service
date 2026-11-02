package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.redis.LeaderLock;
import com.tripbus.booking.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * US-190: completa la comisión de las ventas que no la tienen, cada 15 minutos (catalog no respondió al momento de la
 * venta) y una vez al arrancar (ventas anteriores a este cambio, con la vigencia de SU fecha). Es idempotente: solo
 * toca ventas sin comisión o reembolsos sin reversión. Corre en un único nodo (LeaderLock).
 */
@Component
public class CommissionJob {

    private static final Logger log = LoggerFactory.getLogger(CommissionJob.class);

    /** Ventas por lote; en una pasada se recorren hasta {@link #MAX_LOTES} lotes. */
    static final int LOTE = 200;
    static final int MAX_LOTES = 50;

    private final PaymentRepository paymentRepository;
    private final CommissionService commissionService;
    private final LeaderLock leaderLock;
    private final boolean alArrancar;

    public CommissionJob(PaymentRepository paymentRepository, CommissionService commissionService, LeaderLock leaderLock,
                         @Value("${tripbus.booking.commission-backfill-enabled:true}") boolean alArrancar) {
        this.paymentRepository = paymentRepository;
        this.commissionService = commissionService;
        this.leaderLock = leaderLock;
        this.alArrancar = alArrancar;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (alArrancar) {
            run();
        }
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.commission-ms:900000}", initialDelay = 900000)
    public void scheduled() {
        run();
    }

    void run() {
        leaderLock.runIfLeader("booking-commission", Duration.ofMinutes(14), this::completar);
    }

    /** Devuelve cuántas ventas quedaron completas en esta pasada. */
    public int completar() {
        Map<String, Optional<CatalogClient.CompanyTerms>> cache = new HashMap<>();
        int completas = 0;
        int pendientes = 0;
        for (int lote = 0; lote < MAX_LOTES; lote++) {
            // Las que no se pudieron completar se saltan con el desplazamiento (siguen pendientes para la próxima).
            List<UUID> ids = paymentRepository.findVentasSinComision(PageRequest.of(0, LOTE + pendientes))
                    .stream().skip(pendientes).toList();
            if (ids.isEmpty()) {
                break;
            }
            for (UUID id : ids) {
                if (commissionService.registrar(id, cache)) {
                    completas++;
                } else {
                    pendientes++;
                }
            }
        }
        if (completas > 0 || pendientes > 0) {
            log.info("Comisiones: {} ventas completadas, {} siguen pendientes (catalog no respondió o sin empresa)",
                    completas, pendientes);
        }
        return completas;
    }
}
