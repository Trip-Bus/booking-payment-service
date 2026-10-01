package com.tripbus.booking.common.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Sobre común de los eventos de dominio que viajan por el stream de Redis "tripbus:events".
 * Campos del registro en el stream: eventId, type, source, occurredAt, payload (JSON).
 */
public record EventEnvelope(String eventId, String type, String source, Instant occurredAt, JsonNode payload) {
}
