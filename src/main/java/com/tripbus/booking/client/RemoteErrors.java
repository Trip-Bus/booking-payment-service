package com.tripbus.booking.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

/**
 * Traduce errores de otros servicios a ApiException conservando el código de error remoto.
 * Errores de red/5xx se marcan como {@link RemoteUnavailableException} (reintentables).
 */
public final class RemoteErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RemoteErrors() {
    }

    public static RuntimeException translate(String service, RestClientException e) {
        if (e instanceof HttpStatusCodeException http && http.getStatusCode().is4xxClientError()) {
            String code = "REMOTE_ERROR";
            String message = service + " rechazó la operación";
            try {
                JsonNode error = MAPPER.readTree(http.getResponseBodyAsString()).path("error");
                code = error.path("code").asText(code);
                message = error.path("message").asText(message);
            } catch (Exception ignored) {
                // cuerpo no JSON
            }
            return new ApiException(HttpStatus.valueOf(http.getStatusCode().value()), code, message);
        }
        return new RemoteUnavailableException(service + " no está disponible: " + e.getMessage(), e);
    }

    /** El servicio remoto no respondió (red, timeout o 5xx): la operación se puede reintentar. */
    public static class RemoteUnavailableException extends RuntimeException {
        public RemoteUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
