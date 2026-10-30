package com.tripbus.booking.client;

import com.tripbus.booking.common.error.ApiException;
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

    /** US-166: reglas de la tarifa de oferta. permiteReprogramar null se toma como true (ofertas anteriores). */
    public record OfferDetails(UUID offerId, UUID itineraryId, UUID companyId, BigDecimal precioOferta,
                               BigDecimal precioBase, Boolean permiteReprogramar) {

        public boolean reprogramable() {
            return !Boolean.FALSE.equals(permiteReprogramar);
        }
    }

    /**
     * US-166: GET /internal/offers/{id}. Si flash-offers no responde (o responde un error) lanza 503
     * OFFERS_UNAVAILABLE: sin sus reglas no se puede decidir si la tarifa se reprograma.
     */
    public OfferDetails offer(UUID offerId) {
        OfferDetails details;
        try {
            details = restClient.get().uri("/internal/offers/{offerId}", offerId).retrieve().body(OfferDetails.class);
        } catch (RestClientException e) {
            throw offersUnavailable();
        }
        if (details == null) {
            throw offersUnavailable();
        }
        return details;
    }

    private static ApiException offersUnavailable() {
        return ApiException.unavailable("OFFERS_UNAVAILABLE",
                "No se pudieron consultar las reglas de la oferta; intenta de nuevo");
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
