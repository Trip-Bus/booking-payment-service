package com.tripbus.booking.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Correlación de logs: cada petición lleva un requestId en el MDC (lo imprimen todos los logs de
 * esa petición) y en la respuesta. Si el API Gateway ya mandó X-Request-Id se reutiliza, así la
 * misma petición se puede seguir entre servicios. Corre antes que Spring Security para que hasta
 * un 401 quede correlacionado, y al terminar escribe una línea de acceso (método, ruta, estado, ms).
 *
 * <p>HT-10: va justo después de {@code ServerHttpObservationFilter} (HIGHEST_PRECEDENCE + 1), que abre el span de
 * la petición: así la línea de acceso también lleva traceId y spanId. Si la ruta es de una reserva
 * ({@code /bookings/{id}/...}) su id queda en el MDC como {@code bookingId}.
 */
@Component
@Order(RequestIdFilter.ORDEN)
public class RequestIdFilter extends OncePerRequestFilter {

    /** Después del filtro de observaciones de Spring Boot y antes de Spring Security (-100). */
    public static final int ORDEN = Ordered.HIGHEST_PRECEDENCE + 2;

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String MDC_BOOKING = "bookingId";

    /** Solo valores cortos y seguros: evita que un cliente inyecte saltos de línea en los logs. */
    private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern RUTA_RESERVA = Pattern.compile(
            "^/bookings/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

    /** Id de la reserva de la ruta, o null si la ruta no es de una reserva. */
    public static String bookingIdDe(String uri) {
        Matcher m = RUTA_RESERVA.matcher(uri == null ? "" : uri);
        return m.matches() ? m.group(1) : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || !VALIDO.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        String bookingId = bookingIdDe(request.getRequestURI());
        if (bookingId != null) {
            MDC.put(MDC_BOOKING, bookingId);
        }
        response.setHeader(HEADER, requestId);
        long inicio = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // Log de acceso (una línea por petición) para poder buscar cualquier petición por su
            // requestId. Se omite /actuator: el healthcheck de Docker llama cada 10 s.
            if (!request.getRequestURI().startsWith("/actuator")) {
                log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), (System.nanoTime() - inicio) / 1_000_000);
            }
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_BOOKING);
        }
    }
}
