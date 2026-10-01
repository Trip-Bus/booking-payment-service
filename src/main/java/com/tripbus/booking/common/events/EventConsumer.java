package com.tripbus.booking.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            List<DomainEventHandler> handlers) {
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
            EventEnvelope envelope = toEnvelope(fields);
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        if (processedEvents.existsById(envelope.eventId())) {
                            return;
                        }
                        handler.handle(envelope);
                        processedEvents.save(new ProcessedEvent(envelope.eventId(), type));
                    });
                    break;
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
        redis.opsForStream().acknowledge(properties.stream(), properties.group(), record.getId());
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
