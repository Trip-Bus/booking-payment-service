package com.tripbus.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.domain.SagaLogEntry;
import com.tripbus.booking.repository.SagaLogRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** Persiste cada paso de la saga de compra (participa en la transacción actual). */
@Component
public class SagaLogger {

    private final SagaLogRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SagaLogger(SagaLogRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void log(UUID bookingId, String paso, String estado, Map<String, ?> payload) {
        String json;
        try {
            json = payload == null ? null : objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            json = String.valueOf(payload);
        }
        repository.save(new SagaLogEntry(bookingId, paso, estado, json, clock.instant()));
    }
}
