package com.tripbus.booking.client;

import com.sun.net.httpserver.HttpServer;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.config.BookingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US-138/US-141: POST /internal/pricing de catalog contra un servidor HTTP del JDK. */
class CatalogPricingTest {

    private HttpServer server;
    private final Map<String, String> recibido = new ConcurrentHashMap<>();
    /** Lo que responde el servidor: estado HTTP y cuerpo. */
    private volatile int estado = 200;
    private volatile String respuesta;

    @BeforeEach
    void levantar() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/pricing", exchange -> {
            recibido.put("cuerpo", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            recibido.put("token", String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Token")));
            byte[] body = respuesta.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(estado, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void detener() {
        server.stop(0);
    }

    private CatalogClient cliente(String token) {
        String url = "http://localhost:" + server.getAddress().getPort();
        return new CatalogClient(new BookingProperties(url, url, url, null, "s"), token);
    }

    private static CatalogClient.PricingRequest pedido(int pasajeros) {
        List<CatalogClient.PricingPassenger> lista = new java.util.ArrayList<>();
        for (int i = 0; i < pasajeros; i++) {
            lista.add(new CatalogClient.PricingPassenger(String.valueOf(i + 1), "ADULTO"));
        }
        return new CatalogClient.PricingRequest(UUID.randomUUID(), 2, 4, lista);
    }

    @Test
    void devuelveElPrecioPorPasajeroYElTramoConElTokenInterno() {
        respuesta = """
                {"estado":"PROGRAMADO","moneda":"COP","tramo":{"fromOrden":2,"paradaAbordaje":"Fusagasugá",
                 "toOrden":4,"paradaBajada":"Ibagué"},
                 "pasajeros":[{"asiento":"1","tipoPasajero":"ADULTO","categoriaAsiento":"VIP","precioBase":100000,
                 "descuentoTipo":0,"recargoCategoria":15000,"precio":115000}]}""";

        CatalogClient.PricingResult r = cliente("token").pricing(pedido(1));

        assertThat(r.pasajeros().get(0).precio()).isEqualByComparingTo("115000");
        assertThat(r.tramo().paradaAbordaje()).isEqualTo("Fusagasugá");
        assertThat(recibido).containsEntry("token", "token");
        assertThat(recibido.get("cuerpo")).contains("\"fromOrden\":2").contains("\"tipoPasajero\":\"ADULTO\"");
    }

    @Test
    void sinTokenNoMandaElHeader() {
        respuesta = """
                {"pasajeros":[{"asiento":"1","precio":90000}]}""";

        assertThat(cliente(null).pricing(pedido(1)).pasajeros()).hasSize(1);
        assertThat(recibido).containsEntry("token", "null");
    }

    @Test
    void siCatalogRechazaSeConservaSuCodigo() {
        estado = 409;
        respuesta = """
                {"error":{"code":"INVALID_SEGMENT","message":"El tramo no es válido"}}""";

        assertThatThrownBy(() -> cliente("t").pricing(pedido(1)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("INVALID_SEGMENT");
                });
    }

    @Test
    void respuestasIncompletasOErroresDeCatalogSonPricingUnavailable() {
        CatalogClient catalog = cliente("t");
        for (String incompleta : List.of("null", "{}", "{\"pasajeros\":[]}",
                "{\"pasajeros\":[{\"asiento\":\"1\"}]}", "{\"pasajeros\":[null]}")) {
            respuesta = incompleta;
            assertThatThrownBy(() -> catalog.pricing(pedido(1))).as(incompleta)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.getCode()).isEqualTo("PRICING_UNAVAILABLE");
                    });
        }
        estado = 500;
        respuesta = "{}";
        assertThatThrownBy(() -> catalog.pricing(pedido(1))).isInstanceOf(ApiException.class)
                .hasMessageContaining("No se pudo calcular el precio");
        CatalogClient caido = new CatalogClient(new BookingProperties("http://localhost:1", "http://localhost:1",
                null, null, "s"), "t");
        assertThatThrownBy(() -> caido.pricing(pedido(1))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PRICING_UNAVAILABLE"));
    }
}
