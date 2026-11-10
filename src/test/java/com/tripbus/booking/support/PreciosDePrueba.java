package com.tripbus.booking.support;

import com.tripbus.booking.client.CatalogClient;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Pruebas de integración anteriores al Sprint 6: simulan catalog con {@code price(itineraryId)}. Esto hace que
 * {@code pricing} (POST /internal/pricing) responda igual: el precio base del viaje para cada pasajero, sin recargos,
 * con el estado del viaje. Si el viaje no tiene precio simulado, responde como si catalog no respondiera.
 */
public final class PreciosDePrueba {

    private PreciosDePrueba() {
    }

    public static void desdePrecioBase(CatalogClient catalog) {
        doAnswer(inv -> {
            CatalogClient.PricingRequest r = inv.getArgument(0);
            CatalogClient.Price precio = catalog.price(r.itineraryId());
            if (precio == null) {
                throw com.tripbus.booking.common.error.ApiException.unavailable("PRICING_UNAVAILABLE", "sin precio");
            }
            return new CatalogClient.PricingResult(r.itineraryId(), precio.estado(), precio.moneda(), null,
                    r.pasajeros().stream().map(p -> new CatalogClient.PassengerPrice(p.asiento(), p.tipoPasajero(),
                            "ESTANDAR", precio.precioBase(), BigDecimal.ZERO, BigDecimal.ZERO, precio.precioBase()))
                            .toList());
        }).when(catalog).pricing(any());
    }
}
