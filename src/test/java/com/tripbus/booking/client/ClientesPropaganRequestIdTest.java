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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los clientes salientes (catalog, seat-inventory, flash-offers) llevan el requestId de la petición
 * en curso. Un servidor HTTP del JDK hace de los tres servicios y guarda el X-Request-Id que recibe.
 */
class ClientesPropaganRequestIdTest {

    private static final String TOKEN = "token-interno";
    /** Con este marcador en la ruta o en la consulta el servidor responde un cuerpo JSON null. */
    private static final String VACIO = "00000000-0000-0000-0000-000000000000";
    /** Estados de asientos sin la lista "asientos". */
    private static final String SIN_ASIENTOS = "11111111-1111-1111-1111-111111111111";

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
        String json = "{}";
        String query = String.valueOf(exchange.getRequestURI().getQuery());
        if (path.endsWith(VACIO) || query.contains(VACIO)) {
            json = "null";
        } else if (path.endsWith(SIN_ASIENTOS)) {
            json = "{\"capacidad\":0}";
        } else if (path.startsWith("/trips/search")) {
            recibidos.put(path + "#query", query);
            json = "[{\"itineraryId\":\"" + UUID.randomUUID() + "\",\"asientosDisponibles\":4}]";
        } else if (path.startsWith("/seats/itineraries/")) {
            json = "{\"capacidad\":3,\"asientos\":[{\"seatNumber\":\"1\",\"estado\":\"VENDIDO\"},"
                    + "{\"seatNumber\":\"2\",\"estado\":\"DISPONIBLE\"},{\"seatNumber\":\"3\",\"estado\":\"DISPONIBLE\"}]}";
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
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
    void anularYCambiarAsientosVanASeatInventoryConElTokenInterno() {
        SeatInventoryClient client = new SeatInventoryClient(properties, TOKEN);
        client.voidSale(UUID.randomUUID(), UUID.randomUUID(), List.of("12"), "REPROGRAMACION");
        client.swapSeat(UUID.randomUUID(), UUID.randomUUID(), "12", "14");

        assertThat(recibidos).containsEntry("/internal/sales/void", "prueba-booking-1")
                .containsEntry("/internal/sales/void#token", TOKEN)
                .containsEntry("/internal/sales/swap", "prueba-booking-1")
                .containsEntry("/internal/sales/swap#token", TOKEN);
    }

    @Test
    void busquedaDeViajesAsientosLibresYRetencionInterna() {
        List<CatalogClient.TripOption> viajes = new CatalogClient(properties)
                .search("Bogotá", "Medellín", java.time.LocalDate.of(2026, 10, 27));
        assertThat(viajes).hasSize(1);
        assertThat(viajes.get(0).asientosDisponibles()).isEqualTo(4);
        assertThat(recibidos.get("/trips/search#query")).contains("origin=Bogot").contains("date=2026-10-27");

        SeatInventoryClient client = new SeatInventoryClient(properties, TOKEN);
        assertThat(client.availableSeats(UUID.randomUUID())).containsExactly("2", "3");
        client.createHold(UUID.randomUUID(), List.of("2"), UUID.randomUUID());
        assertThat(recibidos).containsEntry("/internal/holds#token", TOKEN);
    }

    @Test
    void cuerposVaciosYElRestoDeLlamadasInternas() {
        UUID vacio = UUID.fromString(VACIO);
        CatalogClient catalog = new CatalogClient(properties);
        assertThat(catalog.search("Bogotá", VACIO, java.time.LocalDate.of(2026, 10, 27))).isEmpty();
        assertThat(catalog.itinerary(UUID.randomUUID())).isNotNull();

        SeatInventoryClient client = new SeatInventoryClient(properties, TOKEN);
        assertThat(client.availableSeats(vacio)).isEmpty();
        assertThat(client.availableSeats(UUID.fromString(SIN_ASIENTOS))).isEmpty();
        UUID holdId = UUID.randomUUID();
        client.confirmSale(holdId, UUID.randomUUID());
        client.releaseHold(holdId, "PRUEBA");
        assertThat(recibidos).containsKey("/internal/holds/" + holdId + "/confirm")
                .containsKey("/internal/holds/" + holdId + "/release");

        BookingProperties caidos = new BookingProperties("http://localhost:1", "http://localhost:1", null, null, "s");
        assertThat(new CatalogClient(caidos).itinerary(holdId)).isNull();
        SeatInventoryClient caido = new SeatInventoryClient(caidos, TOKEN);
        assertThatThrownBy(() -> caido.getHold(holdId)).isInstanceOf(RemoteErrors.RemoteUnavailableException.class);
        assertThatThrownBy(() -> caido.releaseHold(holdId, "X"))
                .isInstanceOf(RemoteErrors.RemoteUnavailableException.class);
        assertThatThrownBy(() -> new CatalogClient(caidos).price(holdId))
                .isInstanceOf(RemoteErrors.RemoteUnavailableException.class);
    }

    @Test
    void sinRespuestaDeCatalogOSeatInventoryNoSeConcluyeQueNoHayCupo() {
        BookingProperties caidos = new BookingProperties("http://localhost:1", "http://localhost:1", null, null, "s");
        java.time.LocalDate dia = java.time.LocalDate.of(2026, 10, 27);

        assertThatThrownBy(() -> new CatalogClient(caidos).search("A", "B", dia))
                .isInstanceOf(RemoteErrors.RemoteUnavailableException.class);
        SeatInventoryClient client = new SeatInventoryClient(caidos, TOKEN);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> client.availableSeats(id)).isInstanceOf(RemoteErrors.RemoteUnavailableException.class);
        List<String> asientos = List.of("1");
        assertThatThrownBy(() -> client.createHold(id, asientos, id))
                .isInstanceOf(RemoteErrors.RemoteUnavailableException.class);
    }

    @Test
    void seatInventoryCaidoSeTraduceAReintentable() {
        SeatInventoryClient client = new SeatInventoryClient(
                new BookingProperties("http://localhost:1", null, null, null, "secreto"), TOKEN);

        assertThatThrownBy(() -> client.voidSale(UUID.randomUUID(), UUID.randomUUID(), List.of("12"), "REPROGRAMACION"))
                .isInstanceOf(RemoteErrors.RemoteUnavailableException.class);
        assertThatThrownBy(() -> client.swapSeat(UUID.randomUUID(), UUID.randomUUID(), "12", "14"))
                .isInstanceOf(RemoteErrors.RemoteUnavailableException.class);
    }

    @Test
    void lasPropiedadesDeReprogramacionTienenValoresPorDefecto() {
        assertThat(properties.rescheduleCutoff()).isEqualTo(Duration.ofHours(3));
        assertThat(properties.maxReschedules()).isEqualTo(2);
        assertThat(properties.rescheduleFee()).isEqualByComparingTo("0");
        BookingProperties propias = new BookingProperties(null, null, null, null, null, Duration.ofHours(6), 1,
                new BigDecimal("5000"));
        assertThat(propias.rescheduleCutoff()).isEqualTo(Duration.ofHours(6));
        assertThat(propias.maxReschedules()).isEqualTo(1);
        assertThat(propias.rescheduleFee()).isEqualByComparingTo("5000");
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
