package com.tripbus.booking.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.config.BookingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sprint 4: identity por correo (US-144), viaje estricto de catalog (US-144) y reglas de la oferta (US-166). Un
 * servidor HTTP del JDK responde lo que se registre por ruta (status y cuerpo) y guarda el X-Internal-Token.
 */
class ClientesSprint4Test {

    private static final String TOKEN = "token-interno";
    private static final String CAIDO = "http://localhost:1";

    private HttpServer server;
    private String url;
    private final Map<String, Object[]> respuestas = new ConcurrentHashMap<>();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    @BeforeEach
    void levantar() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::responder);
        server.start();
        url = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void detener() {
        server.stop(0);
    }

    private void responder(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        tokens.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Token")));
        Object[] r = respuestas.getOrDefault(query != null ? path + "?" + query : path, new Object[] {404, ""});
        byte[] body = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders((int) r[0], body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private void responde(String ruta, int status, String body) {
        respuestas.put(ruta, new Object[] {status, body});
    }

    // ------------------------------------------------------------------ identity

    @Test
    void identityDevuelveSoloUnPasajeroActivo() {
        UUID id = UUID.randomUUID();
        IdentityClient identity = new IdentityClient(url, TOKEN);
        String ruta = "/internal/users/by-email?email=juan@mail.co";

        responde(ruta, 200, "{\"id\":\"" + id + "\",\"rol\":\"PASAJERO\",\"activo\":true}");
        assertThat(identity.passengerIdByEmail("juan@mail.co")).contains(id);
        assertThat(tokens).containsEntry("/internal/users/by-email", TOKEN);

        responde(ruta, 200, "{\"id\":\"" + id + "\",\"rol\":\"OPERADOR\",\"activo\":true}");
        assertThat(identity.passengerIdByEmail("juan@mail.co")).isEmpty();
        responde(ruta, 200, "{\"id\":\"" + id + "\",\"rol\":\"PASAJERO\",\"activo\":false}");
        assertThat(identity.passengerIdByEmail("juan@mail.co")).isEmpty();
        responde(ruta, 200, "{\"rol\":\"PASAJERO\",\"activo\":true}");
        assertThat(identity.passengerIdByEmail("juan@mail.co")).isEmpty();
        responde(ruta, 200, "");
        assertThat(identity.passengerIdByEmail("juan@mail.co")).isEmpty();
        responde(ruta, 404, "");
        assertThat(identity.passengerIdByEmail("juan@mail.co")).isEmpty();
        responde(ruta, 500, "");
        assertThat(identity.passengerIdByEmail("juan@mail.co")).isEmpty();
        assertThat(new IdentityClient(CAIDO, TOKEN).passengerIdByEmail("juan@mail.co")).isEmpty();
    }

    // ------------------------------------------------------------------ catalog

    @Test
    void catalogDistingueViajeInexistenteDeCatalogCaido() {
        UUID id = UUID.randomUUID();
        CatalogClient catalog = new CatalogClient(new BookingProperties(null, url, null, null, "s"));

        responde("/itineraries/" + id, 200, "{\"itineraryId\":\"" + id + "\",\"origen\":\"Bogotá\"}");
        assertThat(catalog.requireItinerary(id).origen()).isEqualTo("Bogotá");

        responde("/itineraries/" + id, 404, "");
        assertThatThrownBy(() -> catalog.requireItinerary(id)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("ITINERARY_NOT_FOUND"));
        responde("/itineraries/" + id, 200, "");
        assertThatThrownBy(() -> catalog.requireItinerary(id)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("CATALOG_UNAVAILABLE"));
        CatalogClient caido = new CatalogClient(new BookingProperties(null, CAIDO, null, null, "s"));
        assertThatThrownBy(() -> caido.requireItinerary(id)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("CATALOG_UNAVAILABLE"));
    }

    // ------------------------------------------------------------------ flash-offers

    @Test
    void flashOffersDevuelveLasReglasYSiNoRespondeEs503() {
        UUID id = UUID.randomUUID();
        FlashOffersClient flash = new FlashOffersClient(new BookingProperties(null, null, url, null, "s"), TOKEN);

        responde("/internal/offers/" + id, 200, "{\"offerId\":\"" + id + "\",\"permiteReprogramar\":false}");
        FlashOffersClient.OfferDetails details = flash.offer(id);
        assertThat(details.reprogramable()).isFalse();
        assertThat(tokens).containsEntry("/internal/offers/" + id, TOKEN);
        responde("/internal/offers/" + id, 200, "{\"offerId\":\"" + id + "\"}");
        assertThat(flash.offer(id).reprogramable()).isTrue();

        responde("/internal/offers/" + id, 200, "");
        assertThatThrownBy(() -> flash.offer(id)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("OFFERS_UNAVAILABLE"));
        responde("/internal/offers/" + id, 404, "");
        assertThatThrownBy(() -> flash.offer(id)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("OFFERS_UNAVAILABLE"));
    }
}
