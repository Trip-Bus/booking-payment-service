package com.tripbus.booking.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El reenvío del webhook (demostración de idempotencia, US-034) queda encendido en QA y APAGADO en
 * PROD. Carga los application-*.yml reales con cada perfil (sin BD, Redis ni web) y lee el valor que
 * recibe PaymentProperties: /actuator/env no está expuesto, así que esta es la verificación.
 */
class DuplicateWebhookPorPerfilTest {

    @Configuration
    @EnableConfigurationProperties(PaymentProperties.class)
    static class SoloPropiedades {
    }

    @ParameterizedTest(name = "perfil \"{0}\" -> duplicate-webhook={1}")
    @CsvSource({"'', true", "qa, true", "prod, false"})
    void duplicateWebhookSegunElPerfil(String perfil, boolean esperado) {
        List<String> propiedades = new ArrayList<>();
        // Los perfiles desplegados exigen estas variables (RequiredSecretsCheck).
        for (String variable : List.of("DB_PASSWORD", "REDIS_PASSWORD", "INTERNAL_API_TOKEN", "IDENTITY_JWKS_URI",
                "SEAT_INVENTORY_URL", "CATALOG_URL", "FLASH_OFFERS_URL", "TICKET_SECRET",
                "PAYMENT_WEBHOOK_SECRET", "PAYMENT_WEBHOOK_URL")) {
            propiedades.add(variable + "=valor-de-prueba");
        }
        SpringApplicationBuilder builder = new SpringApplicationBuilder(SoloPropiedades.class)
                .web(WebApplicationType.NONE)
                .properties(propiedades.toArray(String[]::new));
        if (!perfil.isEmpty()) {
            builder.profiles(perfil);
        }
        try (ConfigurableApplicationContext context = builder.run()) {
            assertThat(context.getBean(PaymentProperties.class).duplicateWebhook()).isEqualTo(esperado);
        }
    }
}
