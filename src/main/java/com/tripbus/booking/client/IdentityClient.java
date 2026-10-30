package com.tripbus.booking.client;

import com.tripbus.booking.config.RequestIdPropagationInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * US-144: ¿el comprador en taquilla tiene cuenta? Consulta identity ({@code GET /internal/users/by-email},
 * X-Internal-Token). Solo cuenta un PASAJERO activo. Si identity no responde la venta no se detiene: queda a nombre
 * del operador (como un comprador sin cuenta).
 */
@Component
public class IdentityClient {

    private static final Logger log = LoggerFactory.getLogger(IdentityClient.class);

    private final RestClient restClient;

    public IdentityClient(@Value("${tripbus.booking.identity-url}") String identityUrl,
                          @Value("${tripbus.internal-token}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(1500);
        this.restClient = RestClient.builder()
                .baseUrl(identityUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", token)
                .requestInterceptor(new RequestIdPropagationInterceptor())
                .build();
    }

    /** Respuesta de identity: {@code {id, nombre, rol, companyId, activo}}. */
    public record User(UUID id, String nombre, String rol, UUID companyId, boolean activo) {
    }

    /** Id del PASAJERO activo con ese correo; vacío si no hay, no es pasajero o identity no responde. */
    public Optional<UUID> passengerIdByEmail(String email) {
        User user;
        try {
            user = restClient.get().uri(u -> u.path("/internal/users/by-email").queryParam("email", email).build())
                    .retrieve().body(User.class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("No se pudo consultar en identity si el comprador tiene cuenta: {}", e.getMessage());
            return Optional.empty();
        }
        if (user == null || user.id() == null || !user.activo() || !"PASAJERO".equals(user.rol())) {
            return Optional.empty();
        }
        return Optional.of(user.id());
    }
}
