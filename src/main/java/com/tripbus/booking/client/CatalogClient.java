package com.tripbus.booking.client;

import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.config.RequestIdPropagationInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
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

    public record ItinerarySummary(UUID itineraryId, String origen, String destino, Instant fechaSalida,
                                   Instant fechaLlegadaEstimada, String empresa, String placa) {
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
}
