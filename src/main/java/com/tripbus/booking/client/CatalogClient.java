package com.tripbus.booking.client;

import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.config.RequestIdPropagationInterceptor;
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

    public CatalogClient(BookingProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder()
                .baseUrl(properties.catalogUrl())
                .requestFactory(factory)
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
                                   String estado) {

        public ItinerarySummary(UUID itineraryId, String origen, String destino, Instant fechaSalida,
                                Instant fechaLlegadaEstimada, String empresa, String placa, UUID companyId) {
            this(itineraryId, origen, destino, fechaSalida, fechaLlegadaEstimada, empresa, placa, companyId, null);
        }

        public ItinerarySummary(UUID itineraryId, String origen, String destino, Instant fechaSalida,
                                Instant fechaLlegadaEstimada, String empresa, String placa) {
            this(itineraryId, origen, destino, fechaSalida, fechaLlegadaEstimada, empresa, placa, null);
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
