package com.tripbus.booking.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** HT-10: sin OTEL_EXPORTER_OTLP_ENDPOINT no hay endpoint (no hay exportador); LOG_FORMAT=json activa ECS. */
class ObservabilidadEnvironmentTest {

    private final ObservabilidadEnvironment postProcessor = new ObservabilidadEnvironment();

    private MockEnvironment procesar(MockEnvironment env) {
        postProcessor.postProcessEnvironment(env, null);
        return env;
    }

    @Test
    void sinVariablesNoAgregaNadaYLasTrazasQuedanApagadas() {
        MockEnvironment env = procesar(new MockEnvironment());

        assertThat(env.getPropertySources().contains(ObservabilidadEnvironment.FUENTE)).isFalse();
        assertThat(env.getProperty(ObservabilidadEnvironment.PROPIEDAD_ENDPOINT)).isNull();
        assertThat(env.getProperty(ObservabilidadEnvironment.PROPIEDAD_FORMATO_LOG)).isNull();
    }

    @Test
    void endpointVacioOEnBlancoTambienDejaLasTrazasApagadas() {
        MockEnvironment env = procesar(new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "  "));

        assertThat(env.getProperty(ObservabilidadEnvironment.PROPIEDAD_ENDPOINT)).isNull();
    }

    @Test
    void conEndpointExportaAlRecursoDeTrazasDeOtlpHttp() {
        MockEnvironment env = procesar(new MockEnvironment()
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", " http://tempo:4318/ "));

        assertThat(env.getProperty(ObservabilidadEnvironment.PROPIEDAD_ENDPOINT))
                .isEqualTo("http://tempo:4318/v1/traces");
    }

    @Test
    void siElEndpointYaTraeLaRutaNoLaRepite() {
        assertThat(ObservabilidadEnvironment.endpointDeTrazas("http://tempo:4318/v1/traces"))
                .isEqualTo("http://tempo:4318/v1/traces");
        assertThat(ObservabilidadEnvironment.endpointDeTrazas("http://tempo:4318"))
                .isEqualTo("http://tempo:4318/v1/traces");
    }

    @Test
    void logFormatJsonActivaEcsSinImportarMayusculas() {
        MockEnvironment env = procesar(new MockEnvironment().withProperty("LOG_FORMAT", " JSON "));

        assertThat(env.getProperty(ObservabilidadEnvironment.PROPIEDAD_FORMATO_LOG)).isEqualTo("ecs");
    }

    @Test
    void cualquierOtroFormatoDejaElTextoDeSiempre() {
        MockEnvironment env = procesar(new MockEnvironment().withProperty("LOG_FORMAT", "text"));

        assertThat(env.getProperty(ObservabilidadEnvironment.PROPIEDAD_FORMATO_LOG)).isNull();
    }

    @Test
    void correDespuesDeCargarLosArchivosDeConfiguracion() {
        assertThat(postProcessor.getOrder()).isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }
}
