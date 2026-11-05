package com.tripbus.booking.common.events;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * HT-10: el contexto de la traza viaja en los eventos de Redis Streams (campo {@code traceparent}, formato W3C,
 * igual que identity). Al publicar se escribe el de la traza actual; al consumir se abre un span hijo, así un evento
 * se sigue desde el servicio que lo publicó hasta el que lo procesó. Sin traza activa no se escribe nada y el
 * consumidor abre una traza nueva.
 */
@Component
public class EventTrace {

    public static final String CAMPO = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    public EventTrace(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this(tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    public EventTrace(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** Agrega a los campos del evento el contexto de la traza actual (si hay una). */
    public void inyectar(Map<String, String> campos) {
        TraceContext actual = tracer.currentTraceContext().context();
        if (actual != null) {
            propagator.inject(actual, campos, Map::put);
        }
    }

    /** Span del procesamiento de un evento, hijo de la traza que lo publicó. Hay que terminarlo con {@code end()}. */
    public Span iniciar(Map<String, String> campos, String tipo) {
        return propagator.extract(campos, Map::get).name("evento " + tipo).kind(Span.Kind.CONSUMER).start();
    }

    /** Pone el span como actual mientras se procesa el evento (traceId y spanId en los logs). */
    public Tracer.SpanInScope enAlcance(Span span) {
        return tracer.withSpan(span);
    }
}
