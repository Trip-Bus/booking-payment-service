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
import java.util.stream.Collectors;

/** Llamadas internas a Seat Inventory: GetHold, ConfirmSale, ReleaseHold, VoidSale y SwapSeat. */
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

    /** Estado de un asiento en GET /seats/itineraries/{id} (DISPONIBLE, RETENIDO, VENDIDO, EN_OFERTA, BLOQUEADO). */
    public record SeatView(String seatNumber, String estado) {
    }

    public record SeatStates(UUID itineraryId, int capacidad, List<SeatView> asientos) {
    }

    /** US-190: ocupación de un viaje en GET /internal/occupancy (porcentajeOcupacion en %, con dos decimales). */
    public record Occupancy(UUID itineraryId, int capacidad, long vendidos, double porcentajeOcupacion) {
    }

    /** Máximo de viajes por llamada a /internal/occupancy (más responde 400 TOO_MANY_ITINERARIES). */
    public static final int MAX_OCCUPANCY_IDS = 200;

    /**
     * US-190: ocupación de hasta {@link #MAX_OCCUPANCY_IDS} viajes; los desconocidos se omiten. Si seat-inventory
     * no responde lanza {@link RemoteErrors.RemoteUnavailableException}.
     */
    public List<Occupancy> occupancy(List<UUID> itineraryIds) {
        if (itineraryIds.isEmpty()) {
            return List.of();
        }
        try {
            Occupancy[] result = restClient.get()
                    .uri(u -> u.path("/internal/occupancy").queryParam("itineraryIds", itineraryIds.stream()
                            .map(UUID::toString).collect(Collectors.joining(","))).build())
                    .retrieve().body(Occupancy[].class);
            return result == null ? List.of() : List.of(result);
        } catch (RestClientException e) {
            throw RemoteErrors.translate(SERVICE, e);
        }
    }

    /**
     * US-155: asientos DISPONIBLE del viaje en orden natural (como los devuelve seat-inventory). Es la consulta
     * pública de estados, sin usuario.
     */
    public List<String> availableSeats(UUID itineraryId) {
        try {
            SeatStates states = restClient.get().uri("/seats/itineraries/{id}", itineraryId).retrieve()
                    .body(SeatStates.class);
            if (states == null || states.asientos() == null) {
                return List.of();
            }
            return states.asientos().stream().filter(s -> "DISPONIBLE".equals(s.estado()))
                    .map(SeatView::seatNumber).toList();
        } catch (RestClientException e) {
            throw RemoteErrors.translate(SERVICE, e);
        }
    }

    /** US-155: retiene asientos a nombre del pasajero (HoldSeats para otro servicio). 409 SEAT_UNAVAILABLE si se ocuparon. */
    public Hold createHold(UUID itineraryId, List<String> seatNumbers, UUID userId) {
        try {
            return restClient.post().uri("/internal/holds")
                    .body(Map.of("itineraryId", itineraryId, "seatNumbers", seatNumbers, "userId", userId))
                    .retrieve().body(Hold.class);
        } catch (RestClientException e) {
            throw RemoteErrors.translate(SERVICE, e);
        }
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

    public Hold extendHold(UUID holdId, long seconds) {
        try {
            return restClient.post().uri("/internal/holds/{id}/extend", holdId).body(Map.of("segundos", seconds))
                    .retrieve().body(Hold.class);
        } catch (RestClientException e) { throw RemoteErrors.translate(SERVICE, e); }
    }

    public void releaseHold(UUID holdId, String motivo) {
        try {
            restClient.post().uri("/internal/holds/{id}/release", holdId)
                    .body(Map.of("motivo", motivo)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw RemoteErrors.translate(SERVICE, e);
        }
    }

    /**
     * US-150/US-151: devuelve al inventario asientos vendidos a la reserva. Idempotente en seat-inventory
     * (repetirla responde 200 sin cambios). motivo: REPROGRAMACION, CANCELACION_EMPRESA, REEMBOLSO o CAMBIO_BUS.
     */
    public void voidSale(UUID itineraryId, UUID bookingId, List<String> seatNumbers, String motivo) {
        try {
            restClient.post().uri("/internal/sales/void")
                    .body(Map.of("itineraryId", itineraryId, "bookingId", bookingId, "seatNumbers", seatNumbers,
                            "motivo", motivo))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw RemoteErrors.translate(SERVICE, e);
        }
    }

    /** US-139: cambia fromSeat por toSeat de la reserva en una sola transacción (409 SEAT_UNAVAILABLE si está ocupado). */
    public void swapSeat(UUID itineraryId, UUID bookingId, String fromSeat, String toSeat) {
        try {
            restClient.post().uri("/internal/sales/swap")
                    .body(Map.of("itineraryId", itineraryId, "bookingId", bookingId, "fromSeat", fromSeat,
                            "toSeat", toSeat))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw RemoteErrors.translate(SERVICE, e);
        }
    }
}
