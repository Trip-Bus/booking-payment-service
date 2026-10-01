package com.tripbus.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.error.ErrorResponse;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.domain.IdempotencyKey;
import com.tripbus.booking.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * US-034: idempotencia del pago con el header Idempotency-Key (TTL 24 h).
 * <ul>
 *   <li>La llave se "reserva" con un INSERT ... ON CONFLICT DO NOTHING (atómico en la BD).</li>
 *   <li>Reintento con la misma llave y el mismo cuerpo: se devuelve la MISMA respuesta guardada.</li>
 *   <li>Misma llave con otro cuerpo: 422. Misma llave aún en proceso: 409.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_\\-:.]{8,100}");

    private final IdempotencyKeyRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate newTx;
    private final BookingProperties properties;
    private final Clock clock;

    public IdempotencyService(IdempotencyKeyRepository repository, JdbcTemplate jdbc, ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager, BookingProperties properties,
                              Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.clock = clock;
    }

    public ResponseEntity<Object> execute(UUID userId, String rawKey, String requestHash,
                                          Supplier<ResponseEntity<?>> action) {
        if (rawKey == null || !KEY_PATTERN.matcher(rawKey).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "El header Idempotency-Key es obligatorio (8-100 caracteres: letras, números, - _ : .)");
        }
        String key = userId + ":" + rawKey;

        if (!reserve(key, userId, requestHash)) {
            IdempotencyKey existing = repository.findById(key).orElse(null);
            if (existing == null || existing.getExpiraEn().isBefore(clock.instant())) {
                if (existing != null) {
                    newTx.executeWithoutResult(s -> repository.deleteById(key));
                }
                if (!reserve(key, userId, requestHash)) {
                    throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "Hay una solicitud igual en proceso");
                }
            } else {
                return replay(existing, requestHash);
            }
        }

        try {
            ResponseEntity<?> response = action.get();
            store(key, response.getStatusCode().value(), response.getBody());
            @SuppressWarnings("unchecked")
            ResponseEntity<Object> result = (ResponseEntity<Object>) response;
            return result;
        } catch (ApiException e) {
            if (e.getStatus().is4xxClientError()) {
                // Un rechazo de negocio también es "la respuesta" de esa llave: el reintento obtiene lo mismo.
                store(key, e.getStatus().value(), ErrorResponse.of(e.getCode(), e.getMessage(), e.getDetails()));
            } else {
                release(key);
            }
            throw e;
        } catch (RuntimeException e) {
            release(key);
            throw e;
        }
    }

    private ResponseEntity<Object> replay(IdempotencyKey existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "La Idempotency-Key ya se usó con una solicitud diferente");
        }
        if (!IdempotencyKey.COMPLETADA.equals(existing.getEstado())) {
            throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "La solicitud original aún se está procesando");
        }
        try {
            JsonNode body = existing.getResponseBody() != null ? objectMapper.readTree(existing.getResponseBody()) : null;
            return ResponseEntity.status(existing.getResponseStatus())
                    .header("Idempotent-Replayed", "true")
                    .body(body);
        } catch (Exception e) {
            throw new IllegalStateException("Respuesta idempotente corrupta", e);
        }
    }

    private boolean reserve(String key, UUID userId, String requestHash) {
        Instant now = clock.instant();
        Integer inserted = newTx.execute(s -> jdbc.update("""
                INSERT INTO idempotency_keys (key, user_id, request_hash, estado, creado_en, expira_en)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (key) DO NOTHING
                """, key, userId, requestHash, IdempotencyKey.EN_PROCESO, Timestamp.from(now),
                Timestamp.from(now.plus(properties.idempotencyTtl()))));
        return inserted != null && inserted == 1;
    }

    private void store(String key, int status, Object body) {
        newTx.executeWithoutResult(s -> repository.findById(key).ifPresent(k -> {
            try {
                k.completar(status, body == null ? null : objectMapper.writeValueAsString(body));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    private void release(String key) {
        try {
            newTx.executeWithoutResult(s -> repository.deleteById(key));
        } catch (RuntimeException e) {
            log.warn("No se pudo liberar la llave de idempotencia {}: {}", key, e.getMessage());
        }
    }

    public static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void purgeExpired() {
        Integer deleted = newTx.execute(s -> repository.deleteExpired(clock.instant()));
        if (deleted != null && deleted > 0) {
            log.info("Llaves de idempotencia vencidas eliminadas: {}", deleted);
        }
    }
}
