package com.tripbus.booking.client;

import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.config.RequestIdPropagationInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Catalog (REST): valida el itinerario y su precio base. */
@Component
public class CatalogClient {

    private final RestClient restClient;
    /** Token entre servicios: solo se envía a /internal/** (las demás consultas a catalog son públicas). */
    private final String internalToken;

    public CatalogClient(BookingProperties properties) {
        this(properties, null);
    }

    public CatalogClient(BookingProperties properties, String internalToken) {
        this(properties, internalToken, ObservationRegistry.NOOP);
    }

    /** HT-10: con el ObservationRegistry de Spring cada llamada es un span y lleva el traceparent. */
    @Autowired
    public CatalogClient(BookingProperties properties, @Value("${tripbus.internal-token}") String internalToken,
                         ObservationRegistry observations) {
        this.internalToken = internalToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder()
                .baseUrl(properties.catalogUrl())
                .requestFactory(factory)
                .observationRegistry(observations)
                .requestInterceptor(new RequestIdPropagationInterceptor())
                .build();
    }

    public record Price(UUID itineraryId, BigDecimal precioBase, String moneda, String estado) {
    }

    /**
     * companyId: empresa dueña del itinerario (US-005, visibilidad del OPERADOR). estado: PROGRAMADO o
     * CANCELADO (US-150: no se reprograma hacia un viaje cancelado).
     */
    public record ItinerarySummary(UUID itineraryId, String origen, String destino, Instant fechaSalida,
                                   Instant fechaLlegadaEstimada, String empresa, String placa, UUID companyId,
                                   String estado, UUID routeId) {

        /** routeId: ruta del viaje (US-147, cupones limitados a ciertas rutas). */
        public ItinerarySummary(UUID itineraryId, String origen, String destino, Instant fechaSalida,
                                Instant fechaLlegadaEstimada, String empresa, String placa, UUID companyId,
                                String estado) {
            this(itineraryId, origen, destino, fechaSalida, fechaLlegadaEstimada, empresa, placa, companyId, estado,
                    null);
        }

        public ItinerarySummary(UUID itineraryId, String origen, String destino, Instant fechaSalida,
                                Instant fechaLlegadaEstimada, String empresa, String placa, UUID companyId) {
            this(itineraryId, origen, destino, fechaSalida, fechaLlegadaEstimada, empresa, placa, companyId, null);
        }

        public ItinerarySummary(UUID itineraryId, String origen, String destino, Instant fechaSalida,
                                Instant fechaLlegadaEstimada, String empresa, String placa) {
            this(itineraryId, origen, destino, fechaSalida, fechaLlegadaEstimada, empresa, placa, null);
        }
    }

    /**
     * US-190: condiciones comerciales de la empresa vigentes en {@code fecha} (hora de Colombia): comisión de TripBus
     * por tiquete (0 a 30 %) y cuota mensual.
     */
    public record CompanyTerms(UUID id, String nombre, String estado, BigDecimal comisionPorcentaje,
                               BigDecimal cuotaMensual, String nit) {

        /** nit: identificación tributaria de la empresa (US-146, comprobantes); null si catalog no lo trae. */
        public CompanyTerms(UUID id, String nombre, String estado, BigDecimal comisionPorcentaje,
                            BigDecimal cuotaMensual) {
            this(id, nombre, estado, comisionPorcentaje, cuotaMensual, null);
        }
    }

    /**
     * US-138/US-141: POST /internal/pricing. Por pasajero, su asiento y su tipo (ADULTO, NINO o ADULTO_MAYOR); el
     * tramo (fromOrden/toOrden) null = el viaje completo.
     */
    public record PricingRequest(UUID itineraryId, Integer fromOrden, Integer toOrden,
                                 List<PricingPassenger> pasajeros) {
    }

    public record PricingPassenger(String asiento, String tipoPasajero) {
    }

    /**
     * Respuesta de catalog: precio por pasajero ya con el tramo, la temporada, el tipo de pasajero y el recargo de la
     * categoría del asiento (precio = precioBase - descuentoTipo + recargoCategoria), y los nombres de las paradas.
     */
    public record PricingResult(UUID itineraryId, String estado, String moneda, Tramo tramo,
                                List<PassengerPrice> pasajeros) {
    }

    public record Tramo(Integer fromOrden, String paradaAbordaje, Integer toOrden, String paradaBajada) {
    }

    public record PassengerPrice(String asiento, String tipoPasajero, String categoriaAsiento, BigDecimal precioBase,
                                 BigDecimal descuentoTipo, BigDecimal recargoCategoria, BigDecimal precio) {
    }

    /**
     * US-138/US-141: el precio de la compra. Si catalog no responde, falla o responde algo incompleto lanza 503
     * PRICING_UNAVAILABLE (no se crea la reserva); si rechaza la consulta (4xx) se conserva su código de error.
     */
    public PricingResult pricing(PricingRequest request) {
        PricingResult result;
        try {
            result = restClient.post().uri("/internal/pricing")
                    .headers(h -> {
                        if (internalToken != null) {
                            h.set("X-Internal-Token", internalToken);
                        }
                    })
                    .body(request).retrieve().body(PricingResult.class);
        } catch (HttpClientErrorException e) {
            throw RemoteErrors.translate("Catalog", e);
        } catch (RestClientException e) {
            throw pricingUnavailable();
        }
        if (result == null || result.pasajeros() == null || result.pasajeros().size() != request.pasajeros().size()
                || result.pasajeros().stream().anyMatch(p -> p == null || p.precio() == null)) {
            throw pricingUnavailable();
        }
        return result;
    }

    private static ApiException pricingUnavailable() {
        return ApiException.unavailable("PRICING_UNAVAILABLE",
                "No se pudo calcular el precio del viaje; intenta de nuevo en unos minutos");
    }

    /**
     * US-190: GET /internal/companies/{id}?fecha=. Devuelve null si catalog no responde, falla o no trae la comisión:
     * quien llama no frena la venta (la comisión la completa CommissionJob).
     */
    public CompanyTerms companyTerms(UUID companyId, LocalDate fecha) {
        try {
            CompanyTerms terms = restClient.get()
                    .uri(u -> u.path("/internal/companies/{id}").queryParam("fecha", fecha).build(companyId))
                    .headers(h -> {
                        if (internalToken != null) {
                            h.set("X-Internal-Token", internalToken);
                        }
                    })
                    .retrieve().body(CompanyTerms.class);
            return terms != null && terms.comisionPorcentaje() != null ? terms : null;
        } catch (RestClientException e) {
            return null;
        }
    }

    public Price price(UUID itineraryId) {
        try {
            return restClient.get().uri("/itineraries/{id}/price", itineraryId).retrieve().body(Price.class);
        } catch (RestClientException e) {
            throw RemoteErrors.translate("Catalog", e);
        }
    }

    public ItinerarySummary itinerary(UUID itineraryId) {
        try {
            return restClient.get().uri("/itineraries/{id}", itineraryId).retrieve().body(ItinerarySummary.class);
        } catch (RestClientException e) {
            return null;
        }
    }

    /**
     * US-144: el viaje, distinguiendo "no existe" de "catalog no responde": 404 ITINERARY_NOT_FOUND si catalog
     * responde 404 y 503 CATALOG_UNAVAILABLE si no responde, falla o responde sin cuerpo.
     */
    public ItinerarySummary requireItinerary(UUID itineraryId) {
        ItinerarySummary summary;
        try {
            summary = restClient.get().uri("/itineraries/{id}", itineraryId).retrieve().body(ItinerarySummary.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound("ITINERARY_NOT_FOUND", "El viaje no existe");
        } catch (RestClientException e) {
            throw catalogUnavailable();
        }
        if (summary == null) {
            throw catalogUnavailable();
        }
        return summary;
    }

    private static ApiException catalogUnavailable() {
        return ApiException.unavailable("CATALOG_UNAVAILABLE", "No se pudo consultar el viaje; intenta de nuevo");
    }

    /**
     * Un resultado de GET /trips/search. asientosDisponibles es null si catalog no pudo consultar a seat-inventory
     * (disponibilidad desconocida).
     */
    public record TripOption(UUID itineraryId, UUID routeId, String origen, String destino, Instant fechaSalida,
                             Integer asientosDisponibles) {
    }

    /**
     * US-155: viajes programados de la ruta en ese día (hora de Colombia), ordenados por salida. Si catalog no
     * responde lanza {@link RemoteErrors.RemoteUnavailableException}: quien llama no debe concluir "no hay cupo".
     */
    public List<TripOption> search(String origen, String destino, LocalDate fecha) {
        try {
            TripOption[] trips = restClient.get()
                    .uri(u -> u.path("/trips/search").queryParam("origin", origen).queryParam("destination", destino)
                            .queryParam("date", fecha).build())
                    .retrieve().body(TripOption[].class);
            return trips == null ? List.of() : List.of(trips);
        } catch (RestClientException e) {
            throw RemoteErrors.translate("Catalog", e);
        }
    }
}
