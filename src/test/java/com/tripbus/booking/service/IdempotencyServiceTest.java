package com.tripbus.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.domain.IdempotencyKey;
import com.tripbus.booking.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-034: Idempotency-Key del pago, con la BD simulada. */
class IdempotencyServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-26T12:00:00Z");
    private static final String LLAVE = "pago-0001";

    private final IdempotencyKeyRepository repository = mock(IdempotencyKeyRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final UUID userId = UUID.randomUUID();
    private final String key = userId + ":" + LLAVE;
    private IdempotencyService service;

    @BeforeEach
    void preparar() {
        when(tm.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        service = new IdempotencyService(repository, jdbc, new ObjectMapper(), tm,
                new BookingProperties(null, null, null, null, "s"), Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private void reserva(int... resultados) {
        var stub = when(jdbc.update(anyString(), any(Object[].class)));
        for (int r : resultados) {
            stub = stub.thenReturn(r);
        }
    }

    private IdempotencyKey guardada(String hash, Instant expira) {
        IdempotencyKey k = new IdempotencyKey(key, userId, hash, AHORA.minusSeconds(60), expira);
        when(repository.findById(key)).thenReturn(Optional.of(k));
        return k;
    }

    @Test
    void laPrimeraVezEjecutaYGuardaLaRespuesta() {
        reserva(1);
        IdempotencyKey k = guardada("h", AHORA.plusSeconds(60));

        ResponseEntity<Object> r = service.execute(userId, LLAVE, "h",
                () -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("paymentId", "p1")));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(k.getEstado()).isEqualTo(IdempotencyKey.COMPLETADA);
        assertThat(k.getResponseStatus()).isEqualTo(202);
        assertThat(k.getResponseBody()).contains("p1");
    }

    @Test
    void elReintentoDevuelveLaMismaRespuestaYConOtroCuerpoEs422() {
        reserva(0);
        IdempotencyKey k = guardada("h", AHORA.plusSeconds(60));
        k.completar(202, "{\"paymentId\":\"p1\"}");

        ResponseEntity<Object> r = service.execute(userId, LLAVE, "h", () -> {
            throw new AssertionError("no debe ejecutarse de nuevo");
        });
        assertThat(r.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(String.valueOf(r.getBody())).contains("p1");

        assertThatThrownBy(() -> service.execute(userId, LLAVE, "otro", () -> ResponseEntity.ok().build()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void unaLlaveEnProcesoEs409YUnaRespuestaSinCuerpoSeRepiteSinCuerpo() {
        reserva(0);
        IdempotencyKey k = guardada("h", AHORA.plusSeconds(60));
        assertThatThrownBy(() -> service.execute(userId, LLAVE, "h", () -> ResponseEntity.ok().build()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));

        k.completar(204, null);
        assertThat(service.execute(userId, LLAVE, "h", () -> ResponseEntity.ok().build()).getBody()).isNull();
    }

    @Test
    void unaLlaveVencidaSeBorraYSeVuelveAReservar() {
        reserva(0, 1);
        guardada("h", AHORA.minusSeconds(1));

        ResponseEntity<Object> r = service.execute(userId, LLAVE, "h", () -> ResponseEntity.ok("nuevo"));

        assertThat(r.getBody()).isEqualTo("nuevo");
        verify(repository).deleteById(key);
    }

    @Test
    void siOtraSolicitudLaReservaEntreTantoEs409() {
        reserva(0, 0);
        when(repository.findById(key)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(userId, LLAVE, "h", () -> ResponseEntity.ok().build()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));
        verify(repository, never()).deleteById(any());
    }

    @Test
    void unRechazoDeNegocioQuedaComoLaRespuestaYUnFalloLiberaLaLlave() {
        reserva(1, 1, 1);
        IdempotencyKey k = guardada("h", AHORA.plusSeconds(60));

        assertThatThrownBy(() -> service.execute(userId, LLAVE, "h", () -> {
            throw ApiException.conflict("BOOKING_NOT_PAYABLE", "no");
        })).isInstanceOf(ApiException.class);
        assertThat(k.getResponseStatus()).isEqualTo(409);
        assertThat(k.getResponseBody()).contains("BOOKING_NOT_PAYABLE");

        assertThatThrownBy(() -> service.execute(userId, LLAVE, "h", () -> {
            throw ApiException.unavailable("PAYMENT_GATEWAY_UNAVAILABLE", "caída");
        })).isInstanceOf(ApiException.class);
        doThrow(new IllegalStateException("BD caída")).when(repository).deleteById(key);
        assertThatThrownBy(() -> service.execute(userId, LLAVE, "h", () -> {
            throw new IllegalStateException("inesperado");
        })).isInstanceOf(IllegalStateException.class).hasMessage("inesperado");
        verify(repository, org.mockito.Mockito.times(2)).deleteById(key);
    }

    @Test
    void laLlaveDebeTenerUnFormatoValidoYLasVencidasSePurgan() {
        assertThatThrownBy(() -> service.execute(userId, null, "h", () -> ResponseEntity.ok().build()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_IDEMPOTENCY_KEY"));
        assertThatThrownBy(() -> service.execute(userId, "corta", "h", () -> ResponseEntity.ok().build()))
                .isInstanceOf(ApiException.class);

        when(repository.deleteExpired(AHORA)).thenReturn(3, 0);
        service.purgeExpired();
        service.purgeExpired();
        verify(repository, org.mockito.Mockito.times(2)).deleteExpired(AHORA);
        assertThat(new IdempotencyKey(key, userId, "h", AHORA, AHORA).getKey()).isEqualTo(key);
    }
}
