package com.tripbus.booking.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.config.RequestIdFilter;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** HT-10: el consumidor procesa cada evento en su span y con el bookingId del payload en el MDC; reintenta 3 veces. */
class EventConsumerTest {

    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private EventConsumer consumidor(DomainEventHandler handler) {
        doAnswer(inv -> {
            inv.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        return new EventConsumer(mock(RedisConnectionFactory.class), mock(StringRedisTemplate.class), mapper,
                new EventProperties(true, "s", 10, "g", true), tx, processed, java.util.List.of(handler),
                new EventTrace(Tracer.NOOP, Propagator.NOOP));
    }

    private static DomainEventHandler manejador(AtomicReference<String> bookingId, AtomicInteger llamadas,
                                                boolean falla) {
        return new DomainEventHandler() {
            @Override
            public Set<String> eventTypes() {
                return Set.of("Prueba");
            }

            @Override
            public void handle(EventEnvelope event) {
                llamadas.incrementAndGet();
                bookingId.set(MDC.get(RequestIdFilter.MDC_BOOKING));
                if (falla) {
                    throw new IllegalStateException("falla");
                }
            }
        };
    }

    @Test
    void conBookingIdEnElPayloadQuedaEnElMdcMientrasSeProcesa() throws Exception {
        AtomicReference<String> visto = new AtomicReference<>();
        AtomicInteger llamadas = new AtomicInteger();
        DomainEventHandler handler = manejador(visto, llamadas, false);
        EventConsumer consumer = consumidor(handler);

        consumer.procesar(handler, new EventEnvelope("e1", "Prueba", "t", Instant.now(),
                mapper.readTree("{\"bookingId\":\"b-1\"}")), Map.of());

        assertThat(visto.get()).isEqualTo("b-1");
        assertThat(MDC.get(RequestIdFilter.MDC_BOOKING)).isNull();
    }

    @Test
    void sinPayloadOSinBookingIdNoSePoneNadaEnElMdc() throws Exception {
        AtomicReference<String> visto = new AtomicReference<>("x");
        AtomicInteger llamadas = new AtomicInteger();
        DomainEventHandler handler = manejador(visto, llamadas, false);
        EventConsumer consumer = consumidor(handler);

        consumer.procesar(handler, new EventEnvelope("e2", "Prueba", "t", Instant.now(), null), Map.of());
        assertThat(visto.get()).isNull();
        consumer.procesar(handler, new EventEnvelope("e3", "Prueba", "t", Instant.now(), mapper.readTree("{}")),
                Map.of());
        assertThat(visto.get()).isNull();
        assertThat(llamadas.get()).isEqualTo(2);
    }

    @Test
    void unManejadorQueFallaSeReintentaTresVecesYSeDescarta() throws Exception {
        AtomicInteger llamadas = new AtomicInteger();
        DomainEventHandler handler = manejador(new AtomicReference<>(), llamadas, true);
        EventConsumer consumer = consumidor(handler);

        consumer.procesar(handler, new EventEnvelope("e4", "Prueba", "t", Instant.now(), mapper.readTree("{}")),
                Map.of());

        assertThat(llamadas.get()).isEqualTo(3);
    }
}
