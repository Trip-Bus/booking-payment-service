package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Passenger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * US-138/US-141: el precio de una compra. Lo calcula catalog con POST /internal/pricing (tramo, temporada, tipo de
 * pasajero y recargo por categoría de asiento); booking solo suma el desglose. La compra en la app, la reprogramación
 * y la taquilla usan este mismo cálculo. Si catalog no responde: 503 PRICING_UNAVAILABLE y no se crea la reserva.
 *
 * <p>Una retención de oferta relámpago (US-166) conserva el precio de la oferta para cada asiento.
 */
@Service
public class TarifaService {

    public static final String ADULTO = Passenger.TIPO_ADULTO;
    public static final String NINO = "NINO";
    public static final String ADULTO_MAYOR = "ADULTO_MAYOR";
    private static final Set<String> TIPOS = Set.of(ADULTO, NINO, ADULTO_MAYOR);
    private static final String PROGRAMADO = "PROGRAMADO";

    /** Precio de un pasajero: precio = precioBase - descuentoTipo + recargoCategoria. */
    public record PrecioPasajero(String asiento, String tipoPasajero, String categoriaAsiento, BigDecimal precioBase,
                                 BigDecimal descuentoTipo, BigDecimal recargoCategoria, BigDecimal precio) {

        /** Guarda el desglose en el pasajero. */
        public void aplicarA(Passenger pasajero) {
            pasajero.asignarTarifa(tipoPasajero, categoriaAsiento, precioBase, descuentoTipo, recargoCategoria, precio);
        }
    }

    /** subtotal = suma de los precios por pasajero; tramo con los nombres de las paradas (null = viaje completo). */
    public record Tarifa(String moneda, BigDecimal subtotal, List<PrecioPasajero> pasajeros, Booking.Tramo tramo,
                         boolean deOferta) {
    }

    private final CatalogClient catalog;
    private final FlashOffersClient flashOffers;

    public TarifaService(CatalogClient catalog, FlashOffersClient flashOffers) {
        this.catalog = catalog;
        this.flashOffers = flashOffers;
    }

    /** Tipo de pasajero normalizado: ADULTO por defecto; otro valor responde 400 INVALID_PASSENGER_TYPE. */
    public static String tipo(String valor) {
        if (valor == null || valor.isBlank()) {
            return ADULTO;
        }
        String tipo = valor.trim().toUpperCase(Locale.ROOT);
        if (!TIPOS.contains(tipo)) {
            throw ApiException.badRequest("INVALID_PASSENGER_TYPE",
                    "Tipo de pasajero inválido (ADULTO, NINO o ADULTO_MAYOR)");
        }
        return tipo;
    }

    /** Precio de una retención: el de la oferta relámpago si viene de una, o el de catalog para su tramo. */
    public Tarifa calcular(SeatInventoryClient.Hold hold, List<String> tipos, UUID userId) {
        if (hold.offerId() != null) {
            return deOferta(hold, tipos, userId);
        }
        return deCatalog(hold.itineraryId(), hold.seatNumbers(), tipos, hold.fromOrden(), hold.toOrden());
    }

    /** Precio de catalog del viaje completo (taquilla). */
    public Tarifa calcular(UUID itineraryId, List<String> asientos, List<String> tipos) {
        return deCatalog(itineraryId, asientos, tipos, null, null);
    }

    private Tarifa deOferta(SeatInventoryClient.Hold hold, List<String> tipos, UUID userId) {
        FlashOffersClient.OfferReservation reservation = flashOffers.reservationByHold(hold.offerId(), hold.holdId());
        if (!userId.equals(reservation.userId())) {
            throw ApiException.forbidden("OFFER_RESERVATION_MISMATCH", "La reserva de oferta no pertenece al usuario");
        }
        BigDecimal precio = reservation.precioOferta();
        List<PrecioPasajero> pasajeros = new ArrayList<>();
        for (int i = 0; i < hold.seatNumbers().size(); i++) {
            pasajeros.add(new PrecioPasajero(hold.seatNumbers().get(i), tipo(tipoEn(tipos, i)), null, precio,
                    BigDecimal.ZERO, BigDecimal.ZERO, precio));
        }
        Booking.Tramo tramo = hold.fromOrden() == null && hold.toOrden() == null ? null
                : new Booking.Tramo(hold.fromOrden(), null, hold.toOrden(), null);
        return new Tarifa("COP", suma(pasajeros), pasajeros, tramo, true);
    }

    private Tarifa deCatalog(UUID itineraryId, List<String> asientos, List<String> tipos, Integer fromOrden,
                             Integer toOrden) {
        List<CatalogClient.PricingPassenger> pedido = new ArrayList<>();
        for (int i = 0; i < asientos.size(); i++) {
            pedido.add(new CatalogClient.PricingPassenger(asientos.get(i), tipo(tipoEn(tipos, i))));
        }
        CatalogClient.PricingResult result = catalog.pricing(
                new CatalogClient.PricingRequest(itineraryId, fromOrden, toOrden, pedido));
        if (result.estado() != null && !PROGRAMADO.equals(result.estado())) {
            throw ApiException.conflict("ITINERARY_NOT_AVAILABLE", "El viaje ya no está disponible");
        }
        List<PrecioPasajero> pasajeros = new ArrayList<>();
        for (int i = 0; i < pedido.size(); i++) {
            CatalogClient.PassengerPrice p = result.pasajeros().get(i);
            pasajeros.add(new PrecioPasajero(pedido.get(i).asiento(), pedido.get(i).tipoPasajero(),
                    p.categoriaAsiento(), p.precioBase(), p.descuentoTipo(), p.recargoCategoria(), p.precio()));
        }
        CatalogClient.Tramo t = result.tramo();
        Booking.Tramo tramo = t == null || (fromOrden == null && toOrden == null) ? null
                : new Booking.Tramo(t.fromOrden(), t.paradaAbordaje(), t.toOrden(), t.paradaBajada());
        String moneda = result.moneda() != null ? result.moneda() : "COP";
        return new Tarifa(moneda, suma(pasajeros), pasajeros, tramo, false);
    }

    private static String tipoEn(List<String> tipos, int i) {
        return tipos != null && i < tipos.size() ? tipos.get(i) : null;
    }

    private static BigDecimal suma(List<PrecioPasajero> pasajeros) {
        return pasajeros.stream().map(PrecioPasajero::precio).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
