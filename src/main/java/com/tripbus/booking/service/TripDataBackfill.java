package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * US-145: al arrancar, copia de catalog los datos del viaje (origen, destino, salida y empresa) en las reservas que
 * aún no los tienen (las creadas antes de guardarlos). Es idempotente: solo toca las que tienen fecha_salida null.
 * Si catalog no responde para un viaje, sus reservas quedan igual y se reintentan en el siguiente arranque; tras
 * {@link #MAX_FALLOS_SEGUIDOS} fallos seguidos se deja para el próximo arranque (catalog está caído).
 */
@Component
public class TripDataBackfill {

    private static final Logger log = LoggerFactory.getLogger(TripDataBackfill.class);

    static final int MAX_FALLOS_SEGUIDOS = 3;

    private final BookingRepository bookingRepository;
    private final CatalogClient catalog;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public TripDataBackfill(BookingRepository bookingRepository, CatalogClient catalog, TransactionTemplate tx,
                            @Value("${tripbus.booking.trip-backfill-enabled:true}") boolean enabled) {
        this.bookingRepository = bookingRepository;
        this.catalog = catalog;
        this.tx = tx;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (enabled) {
            run();
        }
    }

    /** Devuelve cuántas reservas se rellenaron. */
    public int run() {
        int rellenadas = 0;
        int fallosSeguidos = 0;
        for (UUID itineraryId : bookingRepository.findItinerariosSinViaje()) {
            CatalogClient.ItinerarySummary viaje = catalog.itinerary(itineraryId);
            if (viaje == null || viaje.fechaSalida() == null) {
                fallosSeguidos++;
                if (fallosSeguidos >= MAX_FALLOS_SEGUIDOS) {
                    log.warn("Catalog no responde: los datos del viaje de las reservas se rellenarán en el próximo arranque");
                    break;
                }
                continue;
            }
            fallosSeguidos = 0;
            Integer n = tx.execute(s -> bookingRepository.rellenarViaje(itineraryId, viaje.origen(), viaje.destino(),
                    viaje.fechaSalida(), viaje.empresa()));
            rellenadas += n != null ? n : 0;
        }
        if (rellenadas > 0) {
            log.info("Datos del viaje rellenados en {} reservas", rellenadas);
        }
        return rellenadas;
    }
}
