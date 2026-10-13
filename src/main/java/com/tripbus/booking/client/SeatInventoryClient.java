package com.tripbus.booking.client;

import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.config.RequestIdPropagationInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Llamadas internas a Seat Inventory: GetHold, ConfirmSale y ReleaseHold. */
@Component
public class SeatInventoryClient {

    private static final String SERVICE = "Seat Inventory";

    private final RestClient restClient;

    public SeatInventoryClient(BookingProperties properties,
                               @org.springframework.beans.factory.annotation.Value("${tripbus.internal-token}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder()
                .baseUrl(properties.seatInventoryUrl())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", token)
                .requestInterceptor(new RequestIdPropagationInterceptor())
                .build();
    }

    public record Hold(UUID holdId, UUID itineraryId, List<String> seatNumbers, UUID userId, UUID offerId,
                       UUID bookingId, String estado, Instant expiraEn, long segundosRestantes) {
    }

    public Hold getHold(UUID holdId) {
        try {
            return restClient.get().uri("/internal/holds/{id}", holdId).retrieve().body(Hold.class);
        } catch (RestClientException e) {
            throw RemoteErrors.translate(SERVICE, e);
        }
    }

    public Hold confirmSale(UUID holdId, UUID bookingId) {
        try {
            return restClient.post().uri("/internal/holds/{id}/confirm", holdId)
                    .body(Map.of("bookingId", bookingId)).retrieve().body(Hold.class);
        } catch (RestClientException e) {
            throw RemoteErrors.translate(SERVICE, e);
        }
    }

    public void releaseHold(UUID holdId, String motivo) {
        try {
            restClient.post().uri("/internal/holds/{id}/release", holdId)
                    .body(Map.of("motivo", motivo)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw RemoteErrors.translate(SERVICE, e);
        }
    }
}
