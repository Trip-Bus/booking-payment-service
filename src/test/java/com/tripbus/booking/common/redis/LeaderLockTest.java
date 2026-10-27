package com.tripbus.booking.common.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** El job programado solo corre en la réplica que obtiene la llave en Redis. */
class LeaderLockTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valores = mock(ValueOperations.class);
    private final LeaderLock lock = new LeaderLock(redis);
    private final AtomicInteger corridas = new AtomicInteger();

    @BeforeEach
    void preparar() {
        when(redis.opsForValue()).thenReturn(valores);
    }

    @Test
    void conLaLlaveCorreYSinEllaNo() {
        when(valores.setIfAbsent(eq("job-lock:a"), anyString(), any(Duration.class))).thenReturn(true);
        when(valores.setIfAbsent(eq("job-lock:b"), anyString(), any(Duration.class))).thenReturn(false);
        when(valores.setIfAbsent(eq("job-lock:c"), anyString(), any(Duration.class))).thenReturn(null);

        assertThat(lock.runIfLeader("a", Duration.ofSeconds(5), corridas::incrementAndGet)).isTrue();
        assertThat(lock.runIfLeader("b", Duration.ofSeconds(5), corridas::incrementAndGet)).isFalse();
        assertThat(lock.runIfLeader("c", Duration.ofSeconds(5), corridas::incrementAndGet)).isFalse();
        assertThat(corridas).hasValue(1);
    }

    @Test
    void sinRedisNoCorreYUnFalloDelJobNoSePropaga() {
        when(valores.setIfAbsent(eq("job-lock:caido"), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("caído"));
        when(valores.setIfAbsent(eq("job-lock:falla"), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(lock.runIfLeader("caido", Duration.ofSeconds(5), corridas::incrementAndGet)).isFalse();
        assertThat(lock.runIfLeader("falla", Duration.ofSeconds(5), () -> {
            throw new IllegalStateException("el job falló");
        })).isTrue();
        assertThat(corridas).hasValue(0);
    }
}
