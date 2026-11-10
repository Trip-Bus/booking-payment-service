package com.tripbus.booking;

import com.sun.net.httpserver.HttpServer;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.events.DomainEventHandler;
import com.tripbus.booking.common.events.EventEnvelope;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.common.events.EventTrace;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.config.PaymentProperties;
import com.tripbus.booking.config.RequestIdFilter;
import com.tripbus.booking.events.UserDeletedHandler;
import com.tripbus.booking.observabilidad.MetricasBooking;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.payment.SandboxPaymentGateway;
import com.tripbus.booking.payment.WebhookSigner;
import com.tripbus.booking.service.TicketMailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 5 (HT-10 y US-131) contra PostgreSQL 16 y Redis 7 reales (Testcontainers) con la observabilidad encendida
 * (Micrometer Tracing sobre OpenTelemetry, sin exportar) y el consumidor del stream de eventos activo.
 */
@SpringBootTest(properties = {"tripbus.events.consumer-enabled=true", "tripbus.booking.trip-backfill-enabled=false",
        "tripbus.booking.commission-backfill-enabled=false", "management.tracing.sampling.probability=1.0"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ObservabilidadYUsuarioEliminadoIT {

    private static final String TRAZA = "PruebaTraza";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** Manejador de prueba: guarda la traza y el bookingId con los que el consumidor procesó el evento. */
    static final List<String[]> PROCESADOS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class ManejadorDePrueba {
        @Bean
        DomainEventHandler manejadorDeTraza(Tracer tracer) {
            return new DomainEventHandler() {
                @Override
                public Set<String> eventTypes() {
                    return Set.of(TRAZA);
                }

                @Override
                public void handle(EventEnvelope event) {
                    Span actual = tracer.currentSpan();
                    PROCESADOS.add(new String[]{event.eventId(), actual != null ? actual.context().traceId() : null,
                            MDC.get(RequestIdFilter.MDC_BOOKING)});
                }
            };
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EventPublisher events;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private Tracer tracer;
    @Autowired
    private ObservationRegistry observations;
    @Autowired
    private TicketMailService ticketMail;
    @Autowired
    private MetricasBooking metricas;
    @Autowired
    private EventTrace eventTrace;

    @MockitoBean
    private PaymentGateway gateway;

    private HttpServer server;
    private final Map<String, String> traceparents = new ConcurrentHashMap<>();

    @BeforeAll
    void levantarServidor() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            traceparents.put(exchange.getRequestURI().getPath(),
                    String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
            byte[] body = "{}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    void detener() {
        server.stop(0);
    }

    private String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ datos

    private UUID reserva(UUID userId, String estado, Instant salida, String canal, boolean conTiquete) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO bookings (id, hold_id, user_id, itinerary_id, seat_numbers, estado, precio_unitario,
                            total, moneda, creado_en, actualizado_en, email_contacto, fecha_salida, canal, vendido_por,
                            comprador_nombre, comprador_email, comprador_telefono)
                        VALUES (?, ?, ?, ?, '1', ?, 50000, 50000, 'COP', now(), now(), 'ana.perez@mail.com', ?, ?, ?,
                            ?, ?, ?)""",
                id, UUID.randomUUID(), userId, UUID.randomUUID(), estado, Timestamp.from(salida), canal,
                "TAQUILLA".equals(canal) ? UUID.randomUUID() : null,
                "TAQUILLA".equals(canal) ? "Ana Pérez" : null, "TAQUILLA".equals(canal) ? "ana.perez@mail.com" : null,
                "TAQUILLA".equals(canal) ? "3001234567" : null);
        jdbc.update("INSERT INTO passengers (id, booking_id, nombre, tipo_documento, numero_documento, asiento)"
                + " VALUES (?, ?, 'Ana Pérez', 'CC', '1020304050', '1')", UUID.randomUUID(), id);
        if (conTiquete) {
            jdbc.update("INSERT INTO tickets (id, booking_id, qr_code, qr_token, emitido_en, estado)"
                    + " VALUES (?, ?, 'qr', ?, now(), 'EMITIDO')", UUID.randomUUID(), id,
                    UUID.randomUUID().toString().replace("-", ""));
        }
        return id;
    }

    private Map<String, Object> fila(UUID bookingId) {
        return jdbc.queryForMap("select email_contacto, comprador_nombre, comprador_email, comprador_telefono,"
                + " usuario_eliminado_en from bookings where id = ?", bookingId);
    }

    private Map<String, Object> pasajero(UUID bookingId) {
        return jdbc.queryForMap("select nombre, numero_documento from passengers where booking_id = ?", bookingId);
    }

    // ------------------------------------------------------------------ US-131

    @Test
    void userDeletedBorraLosDatosDeContactoYConservaLosPasajerosDeLosViajes() throws Exception {
        UUID usuario = UUID.randomUUID();
        UUID futura = reserva(usuario, "CONFIRMADA", Instant.now().plus(Duration.ofDays(3)), "APP", true);
        UUID hecha = reserva(usuario, "CONFIRMADA", Instant.now().minus(Duration.ofDays(30)), "APP", false);
        UUID taquilla = reserva(usuario, "CONFIRMADA", Instant.now().plus(Duration.ofDays(1)), "TAQUILLA", false);
        UUID nuncaPagada = reserva(usuario, "EXPIRADA", Instant.now().plus(Duration.ofDays(2)), "APP", false);
        UUID deOtro = reserva(UUID.randomUUID(), "CONFIRMADA", Instant.now().plus(Duration.ofDays(3)), "APP", false);

        String eventId = UUID.randomUUID().toString();
        events.publish(eventId, UserDeletedHandler.USER_DELETED, Map.of("userId", usuario));

        await().atMost(Duration.ofSeconds(15)).until(() -> fila(futura).get("email_contacto") == null);
        for (UUID id : List.of(futura, hecha, taquilla, nuncaPagada)) {
            assertThat(fila(id)).containsEntry("email_contacto", null).containsEntry("comprador_nombre", null)
                    .containsEntry("comprador_email", null).containsEntry("comprador_telefono", null)
                    .extractingByKey("usuario_eliminado_en").isNotNull();
        }
        // Manifiesto y contabilidad: los pasajeros de las reservas pagadas se conservan; los de la que nunca se pagó no.
        assertThat(pasajero(hecha)).containsEntry("nombre", "Ana Pérez").containsEntry("numero_documento", "1020304050");
        assertThat(pasajero(futura)).containsEntry("numero_documento", "1020304050");
        assertThat(pasajero(nuncaPagada)).containsEntry("nombre", "Usuario eliminado")
                .containsEntry("numero_documento", "ELIMINADO");
        // La reserva futura sigue válida, pero el tiquete ya no se envía ni se reenvía por correo.
        assertThat(jdbc.queryForObject("select estado from bookings where id = ?", String.class, futura))
                .isEqualTo("CONFIRMADA");
        UUID ticketId = jdbc.queryForObject("select id from tickets where booking_id = ?", UUID.class, futura);
        assertThat(ticketMail.send(ticketId)).isFalse();
        mvc.perform(post("/tickets/{id}/resend", ticketId).with(jwt().jwt(j -> j.subject(usuario.toString())
                        .claim("role", "PASAJERO")).authorities(new SimpleGrantedAuthority("ROLE_PASAJERO"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NO_CONTACT_EMAIL"));
        // Las reservas de otros usuarios no se tocan.
        assertThat(fila(deOtro).get("email_contacto")).isEqualTo("ana.perez@mail.com");

        // Idempotente por eventId: el mismo evento otra vez no vuelve a procesarse.
        Object marca = fila(futura).get("usuario_eliminado_en");
        events.publish(eventId, UserDeletedHandler.USER_DELETED, Map.of("userId", usuario));
        String otroId = UUID.randomUUID().toString();
        events.publish(otroId, TRAZA, Map.of());
        await().atMost(Duration.ofSeconds(15)).until(() -> PROCESADOS.stream().anyMatch(p -> p[0].equals(otroId)));
        assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Long.class, eventId))
                .isEqualTo(1);
        assertThat(fila(futura).get("usuario_eliminado_en")).isEqualTo(marca);
    }

    @Test
    void userDeletedSinUsuarioValidoSeIgnora() {
        String sinUsuario = UUID.randomUUID().toString();
        String invalido = UUID.randomUUID().toString();
        events.publish(sinUsuario, UserDeletedHandler.USER_DELETED, Map.of());
        events.publish(invalido, UserDeletedHandler.USER_DELETED, Map.of("userId", "no-es-uuid"));

        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "select count(*) from processed_events where event_id in (?, ?)", Long.class, sinUsuario, invalido) == 2);
    }

    // ------------------------------------------------------------------ HT-10

    @Test
    void elEventoLlevaElTraceparentYElConsumidorSigueEnLaMismaTraza() {
        Span span = tracer.nextSpan().name("publicar").start();
        String eventId = UUID.randomUUID().toString();
        String bookingId = UUID.randomUUID().toString();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            events.publish(eventId, TRAZA, Map.of("bookingId", bookingId));
        } finally {
            span.end();
        }

        List<MapRecord<String, Object, Object>> registros = redisTemplate.opsForStream().range("tripbus:events:test",
                org.springframework.data.domain.Range.unbounded());
        assertThat(registros).anySatisfy(r -> {
            assertThat(r.getValue().get("eventId")).isEqualTo(eventId);
            assertThat((String) r.getValue().get(EventTrace.CAMPO)).startsWith("00-" + span.context().traceId() + "-");
        });
        await().atMost(Duration.ofSeconds(15)).until(() -> PROCESADOS.stream().anyMatch(p -> p[0].equals(eventId)));
        String[] procesado = PROCESADOS.stream().filter(p -> p[0].equals(eventId)).findFirst().orElseThrow();
        assertThat(procesado[1]).isEqualTo(span.context().traceId());
        assertThat(procesado[2]).isEqualTo(bookingId);
        assertThat(MDC.get(RequestIdFilter.MDC_BOOKING)).isNull();
    }

    @Test
    void sinTrazaActivaElEventoNoLlevaTraceparent() {
        java.util.Map<String, String> campos = new java.util.LinkedHashMap<>();
        eventTrace.inyectar(campos);
        assertThat(campos).doesNotContainKey(EventTrace.CAMPO);
    }

    @Test
    void losRestClientPropaganLaTraza() {
        CatalogClient catalog = new CatalogClient(new BookingProperties(url(), url(), url(), null, "s"), "t", observations);
        Span span = tracer.nextSpan().name("reserva").start();
        UUID itinerario = UUID.randomUUID();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            catalog.itinerary(itinerario);
        } finally {
            span.end();
        }

        assertThat(traceparents.get("/itineraries/" + itinerario)).startsWith("00-" + span.context().traceId() + "-");
    }

    @Test
    void elWebhookDeLaPasarelaSimuladaSigueEnLaTrazaDelCobro() {
        PaymentProperties props = new PaymentProperties(url() + "/webhooks/payment", "secreto", Duration.ofMillis(10),
                false, null, null, true, null);
        SandboxPaymentGateway pasarela = new SandboxPaymentGateway(props, new WebhookSigner(props), new ObjectMapper(),
                observations, new org.springframework.beans.factory.support.StaticListableBeanFactory(
                        Map.of("tracer", tracer)).getBeanProvider(Tracer.class));
        Span span = tracer.nextSpan().name("cobro").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            pasarela.charge(UUID.randomUUID(), new BigDecimal("50000"), "COP",
                    new PaymentGateway.ChargeRequest("TARJETA", "tok_visa_approved", null, null));
        } finally {
            span.end();
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> traceparents.containsKey("/webhooks/payment"));
        assertThat(traceparents.get("/webhooks/payment")).startsWith("00-" + span.context().traceId() + "-");
    }

    @Test
    void cadaLineaDeLogLlevaRequestIdBookingIdYTraza(CapturedOutput salida) throws Exception {
        UUID bookingId = UUID.randomUUID();
        mvc.perform(get("/bookings/{id}", bookingId).header(RequestIdFilter.HEADER, "req-sprint5")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());

        String linea = salida.getOut().lines().filter(l -> l.contains("GET /bookings/" + bookingId)).findFirst()
                .orElseThrow();
        // Texto ("[req] [bookingId] [traceId,spanId]") o ECS ("requestId":..., "traceId":...): el formato de la JVM
        // depende de qué contexto configuró los logs primero (un contexto prod activa LOG_FORMAT=json).
        if (linea.startsWith("{")) {
            assertThat(linea).contains("\"requestId\":\"req-sprint5\"").contains("\"bookingId\":\"" + bookingId + "\"")
                    .containsPattern("\"traceId\":\"[0-9a-f]{32}\"").containsPattern("\"spanId\":\"[0-9a-f]{16}\"");
        } else {
            assertThat(linea).contains("[req-sprint5]").contains("[" + bookingId + "]")
                    .containsPattern("\\[[0-9a-f]{32},[0-9a-f]{16}]");
        }
        // Nunca correos completos en los logs.
        assertThat(salida.getOut()).doesNotContain("ana.perez@mail.com");
    }

    @Test
    void lasMetricasDeNegocioSalenEnPrometheus() throws Exception {
        metricas.pago("TARJETA", MetricasBooking.APROBADO);
        metricas.reembolso("ASIENTO_NO_CONFIRMADO: HOLD_NOT_ACTIVE");
        metricas.sagaRecuperada();
        metricas.tiqueteEmitido("APP");
        metricas.pagoHastaTiquete(Duration.ofSeconds(3));

        String prometheus = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(prometheus)
                .contains("tripbus_pagos_total{application=\"booking-payment-service\",metodo=\"TARJETA\",resultado=\"aprobado\"}")
                .contains("tripbus_reembolsos_total{application=\"booking-payment-service\",causa=\"asiento_no_confirmado\"}")
                .contains("tripbus_sagas_recuperadas_total{application=\"booking-payment-service\"}")
                .contains("tripbus_tiquetes_emitidos_total{application=\"booking-payment-service\",canal=\"APP\"}")
                .contains("tripbus_pago_hasta_tiquete_segundos_seconds_count{application=\"booking-payment-service\"}");
    }
}
