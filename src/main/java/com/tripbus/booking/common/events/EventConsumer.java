package com.tripbus.booking.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.config.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consume el stream "tripbus:events" con un consumer group por servicio (cada evento lo
 * procesa UNA réplica del servicio). Idempotente por eventId (tabla processed_events).
 * Si un manejador falla se reintenta 3 veces; después se registra el error y se confirma
 * (ack) para no bloquear el stream (dead-letter por log en el MVP).
 */
@Component
public class EventConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    private static final int MAX_ATTEMPTS = 3;
    /** Clave del MDC con la reserva del evento o de la petición en curso. */
    public static final String BOOKING_ID = RequestIdFilter.MDC_BOOKING;

    private final EventTrace trace;

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final EventProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ProcessedEventRepository processedEvents;
    private final Map<String, DomainEventHandler> handlersByType = new HashMap<>();
    private final String consumerName = "c-" + UUID.randomUUID().toString().substring(0, 8);

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile boolean running;

    public EventConsumer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            EventProperties properties,
            TransactionTemplate transactionTemplate,
            ProcessedEventRepository processedEvents,
            List<DomainEventHandler> handlers,
            EventTrace trace) {
        this.trace = trace;
        this.connectionFactory = connectionFactory;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.processedEvents = processedEvents;
        for (DomainEventHandler handler : handlers) {
            for (String type : handler.eventTypes()) {
                handlersByType.put(type, handler);
            }
        }
    }

    @Override
    public void start() {
        if (!properties.enabled() || !properties.consumerEnabled() || handlersByType.isEmpty()) {
            log.info("Consumidor de eventos deshabilitado");
            return;
        }
        ensureGroup();
        var options = StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofSeconds(1))
                .batchSize(50)
                .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);
        var request = StreamMessageListenerContainer.StreamReadRequest
                .builder(StreamOffset.create(properties.stream(), ReadOffset.lastConsumed()))
                .consumer(Consumer.from(properties.group(), consumerName))
                .autoAcknowledge(false)
                .cancelOnError(t -> false)
                .errorHandler(this::onPollError)
                .build();
        container.register(request, this::onMessage);
        container.start();
        running = true;
        log.info("Consumiendo {} como grupo '{}' / consumidor '{}' para tipos {}",
                properties.stream(), properties.group(), consumerName, handlersByType.keySet());
    }

    private void onPollError(Throwable t) {
        String message = String.valueOf(t.getMessage());
        if (message.contains("NOGROUP")) {
            ensureGroup();
        } else {
            log.warn("Error leyendo el stream de eventos: {}", message);
            sleepQuietly(1000);
        }
    }

    private void ensureGroup() {
        try {
            byte[] key = properties.stream().getBytes(StandardCharsets.UTF_8);
            redis.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(key, properties.group(), ReadOffset.from("0-0"), true));
            log.info("Consumer group '{}' creado en {}", properties.group(), properties.stream());
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")
                    && !(e.getCause() != null && String.valueOf(e.getCause().getMessage()).contains("BUSYGROUP"))) {
                log.warn("No se pudo crear el consumer group '{}': {}", properties.group(), e.getMessage());
            }
        }
    }

    private void onMessage(MapRecord<String, String, String> record) {
        Map<String, String> fields = record.getValue();
        String type = fields.get("type");
        DomainEventHandler handler = handlersByType.get(type);
        if (handler != null) {
            procesar(handler, toEnvelope(fields), fields);
        }
        redis.opsForStream().acknowledge(properties.stream(), properties.group(), record.getId());
    }

    /**
     * HT-10: el evento se procesa en un span hijo de la traza que lo publicó (campo traceparent) y con su bookingId
     * en el MDC (si el payload lo trae), así sus logs llevan traceId, spanId y bookingId.
     */
    void procesar(DomainEventHandler handler, EventEnvelope envelope, Map<String, String> fields) {
        Span span = trace.iniciar(fields, envelope.type());
        String bookingId = envelope.payload() != null ? envelope.payload().path("bookingId").asText(null) : null;
        try (Tracer.SpanInScope alcance = trace.enAlcance(span);
             MDC.MDCCloseable mdc = bookingId != null ? MDC.putCloseable(BOOKING_ID, bookingId) : null) {
            reintentar(handler, envelope);
        } finally {
            span.end();
        }
    }

    private void reintentar(DomainEventHandler handler, EventEnvelope envelope) {
        String type = envelope.type();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    if (processedEvents.existsById(envelope.eventId())) {
                        return;
                    }
                    handler.handle(envelope);
                    processedEvents.save(new ProcessedEvent(envelope.eventId(), type));
                });
                return;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Evento {} ({}) descartado tras {} intentos: {}",
                            envelope.eventId(), type, MAX_ATTEMPTS, e.getMessage(), e);
                } else {
                    sleepQuietly(200L * attempt);
                }
            }
        }
    }

    private EventEnvelope toEnvelope(Map<String, String> fields) {
        try {
            JsonNode payload = objectMapper.readTree(fields.getOrDefault("payload", "{}"));
            String occurredAt = fields.get("occurredAt");
            return new EventEnvelope(
                    fields.get("eventId"),
                    fields.get("type"),
                    fields.get("source"),
                    occurredAt != null ? Instant.parse(occurredAt) : Instant.now(),
                    payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Evento mal formado", e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
