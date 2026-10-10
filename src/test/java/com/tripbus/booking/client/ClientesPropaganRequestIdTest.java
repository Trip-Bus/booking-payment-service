package com.tripbus.booking.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.config.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los clientes salientes (catalog, seat-inventory, flash-offers) llevan el requestId de la petición
 * en curso. Un servidor HTTP del JDK hace de los tres servicios y guarda el X-Request-Id que recibe.
 */
class ClientesPropaganRequestIdTest {

    private static final String TOKEN = "token-interno";

    private HttpServer server;
    private final Map<String, String> recibidos = new ConcurrentHashMap<>();
    private BookingProperties properties;

    @BeforeEach
    void levantarServidor() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::responder);
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();
        properties = new BookingProperties(url, url, url, null, "secreto");
        MDC.put(RequestIdFilter.MDC_KEY, "prueba-booking-1");
    }

    @AfterEach
    void detener() {
        MDC.clear();
        server.stop(0);
    }

    private void responder(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        recibidos.put(path, String.valueOf(exchange.getRequestHeaders().getFirst(RequestIdFilter.HEADER)));
        recibidos.put(path + "#token", String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Token")));
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void catalogPropagaElRequestId() {
        UUID itineraryId = UUID.randomUUID();
        new CatalogClient(properties).price(itineraryId);

        assertThat(recibidos).containsEntry("/itineraries/" + itineraryId + "/price", "prueba-booking-1");
    }

    @Test
    void seatInventoryPropagaElRequestIdYElTokenInterno() {
        UUID holdId = UUID.randomUUID();
        new SeatInventoryClient(properties, TOKEN).getHold(holdId);

        String path = "/internal/holds/" + holdId;
        assertThat(recibidos).containsEntry(path, "prueba-booking-1").containsEntry(path + "#token", TOKEN);
    }

    @Test
    void flashOffersPropagaElRequestIdYElTokenInterno() {
        UUID offerId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        new FlashOffersClient(properties, TOKEN).reservationByHold(offerId, holdId);

        String path = "/internal/offers/" + offerId + "/reservations/by-hold/" + holdId;
        assertThat(recibidos).containsEntry(path, "prueba-booking-1").containsEntry(path + "#token", TOKEN);
    }
}
