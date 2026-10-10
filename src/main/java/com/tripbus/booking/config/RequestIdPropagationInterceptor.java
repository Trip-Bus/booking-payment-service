package com.tripbus.booking.config;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Propaga el requestId de la petición en curso (MDC, lo pone {@link RequestIdFilter}) en las
 * llamadas HTTP salientes a seat-inventory, catalog y flash-offers. Así una misma petición del
 * usuario se puede seguir en los logs de todos los servicios con un solo id. Si la llamada sale fuera de una
 * petición (tarea programada) no hay requestId y el servicio destino genera uno propio.
 */
public class RequestIdPropagationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null && !request.getHeaders().containsKey(RequestIdFilter.HEADER)) {
            request.getHeaders().set(RequestIdFilter.HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
