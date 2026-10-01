package com.tripbus.booking.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publica eventos de dominio en el stream de Redis (XADD). Bus de eventos del MVP.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final int TRIM_EVERY = 500;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final EventProperties properties;
    private final String source;
    private final AtomicLong published = new AtomicLong();

    public EventPublisher(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            EventProperties properties,
            @Value("${spring.application.name}") String source) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.source = source;
    }

    /** Publica de inmediato. Lanza excepción si Redis no está disponible. */
    public String publish(String type, Object payload) {
        return publish(UUID.randomUUID().toString(), type, payload);
    }

    public String publish(String eventId, String type, Object payload) {
        if (!properties.enabled()) {
            return eventId;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", eventId);
        fields.put("type", type);
        fields.put("source", source);
        fields.put("occurredAt", Instant.now().toString());
        fields.put("payload", toJson(payload));

        MapRecord<String, String, String> record = StreamRecords.newRecord().in(properties.stream()).ofMap(fields);
        redis.opsForStream().add(record);
        if (published.incrementAndGet() % TRIM_EVERY == 0) {
            redis.opsForStream().trim(properties.stream(), properties.maxLen(), true);
        }
        log.debug("Evento publicado {} {}", type, eventId);
        return eventId;
    }

    /** Publica sin propagar errores (para eventos cuya pérdida no rompe la consistencia). */
    public void publishSafely(String type, Object payload) {
        try {
            publish(type, payload);
        } catch (RuntimeException e) {
            log.error("No se pudo publicar el evento {}: {}", type, e.getMessage());
        }
    }

    /**
     * Si hay una transacción activa, publica solo después del commit (para no anunciar
     * cambios que luego se revierten). Si no hay transacción, publica de inmediato.
     */
    public void publishAfterCommit(String type, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishSafely(type, payload);
                }
            });
        } else {
            publishSafely(type, payload);
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload de evento no serializable", e);
        }
    }
}
