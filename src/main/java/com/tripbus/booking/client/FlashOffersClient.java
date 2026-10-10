package com.tripbus.booking.client;

import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.config.RequestIdPropagationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.UUID;

/** Flash Offers: valida que la retención venga de una reserva de oferta y obtiene su precio. */
@Component
public class FlashOffersClient {

    private final RestClient restClient;

    public FlashOffersClient(BookingProperties properties, @Value("${tripbus.internal-token}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder()
                .baseUrl(properties.flashOffersUrl())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", token)
                .requestInterceptor(new RequestIdPropagationInterceptor())
                .build();
    }

    public record OfferReservation(UUID reservationId, UUID offerId, UUID holdId, UUID userId, String seatNumber,
                                   BigDecimal precioOferta, String estado) {
    }

    public OfferReservation reservationByHold(UUID offerId, UUID holdId) {
        try {
            return restClient.get().uri("/internal/offers/{offerId}/reservations/by-hold/{holdId}", offerId, holdId)
                    .retrieve().body(OfferReservation.class);
        } catch (RestClientException e) {
            throw RemoteErrors.translate("Flash Offers", e);
        }
    }
}
