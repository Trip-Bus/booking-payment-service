package com.tripbus.booking.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HT-10: traduce las variables de observabilidad de la plataforma a propiedades de Spring Boot.
 *
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT}: si tiene valor, los spans se exportan por OTLP/HTTP a esa URL
 *   (se agrega {@code /v1/traces} si no viene, como hace el SDK de OpenTelemetry). Vacía = apagado: no se
 *   define {@code management.otlp.tracing.endpoint} y Spring Boot no crea el exportador. Se hace aquí y no
 *   con {@code ${OTEL_EXPORTER_OTLP_ENDPOINT:}} en el YAML porque un endpoint vacío también activa el
 *   exportador (con una URL inválida).</li>
 *   <li>{@code LOG_FORMAT=json}: logs estructurados ECS en la consola
 *   ({@code logging.structured.format.console=ecs}). Cualquier otro valor deja el texto de siempre.</li>
 * </ul>
 *
 * <p>Corre después de cargar los application-*.yml (así {@code application-prod.yml} puede fijar
 * {@code LOG_FORMAT}) y antes de que se configure el sistema de logs. Se registra en
 * {@code META-INF/spring.factories}.
 */
public class ObservabilidadEnvironment implements EnvironmentPostProcessor, Ordered {

    static final String FUENTE = "tripbusObservabilidad";
    static final String VARIABLE_ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";
    static final String VARIABLE_FORMATO_LOG = "LOG_FORMAT";
    static final String PROPIEDAD_ENDPOINT = "management.otlp.tracing.endpoint";
    static final String PROPIEDAD_FORMATO_LOG = "logging.structured.format.console";
    static final String RUTA_TRAZAS = "/v1/traces";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> propiedades = new LinkedHashMap<>();
        String endpoint = environment.getProperty(VARIABLE_ENDPOINT);
        if (StringUtils.hasText(endpoint)) {
            propiedades.put(PROPIEDAD_ENDPOINT, endpointDeTrazas(endpoint.trim()));
        }
        String formato = environment.getProperty(VARIABLE_FORMATO_LOG);
        if (formato != null && "json".equalsIgnoreCase(formato.trim())) {
            propiedades.put(PROPIEDAD_FORMATO_LOG, "ecs");
        }
        if (!propiedades.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(FUENTE, propiedades));
        }
    }

    static String endpointDeTrazas(String base) {
        String sinBarra = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return sinBarra.endsWith(RUTA_TRAZAS) ? sinBarra : sinBarra + RUTA_TRAZAS;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
