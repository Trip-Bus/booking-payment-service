package com.tripbus.booking.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredSecretsCheckTest {

    private final RequiredSecretsCheck check = new RequiredSecretsCheck();
    private final SpringApplication application = new SpringApplication();

    @Test
    void sinPerfilDesplegadoNoHaceNada() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("tripbus.required-env", "DB_PASSWORD,REDIS_PASSWORD");

        assertThatCode(() -> check.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test
    void conDesplegadoYTodoDefinidoPasa() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("tripbus.required-env", "DB_PASSWORD, REDIS_PASSWORD")
                .withProperty("DB_PASSWORD", "secreto")
                .withProperty("REDIS_PASSWORD", "otro");
        environment.setActiveProfiles("prod", "desplegado");

        assertThatCode(() -> check.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test
    void conVariablesFaltantesLanzaYLasNombra() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("tripbus.required-env", "SEAT_INVENTORY_URL,TICKET_SECRET,PAYMENT_WEBHOOK_SECRET")
                .withProperty("TICKET_SECRET", "")
                .withProperty("SEAT_INVENTORY_URL", "http://seat-inventory:18003");
        environment.setActiveProfiles("prod", "desplegado");

        assertThatThrownBy(() -> check.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Entorno prod,desplegado: faltan variables de entorno obligatorias "
                        + "[TICKET_SECRET, PAYMENT_WEBHOOK_SECRET]");
    }

    @Test
    void uneLasListasDeTodosLosPerfiles() {
        // Si un perfil declara su propia lista, la unión de todas no debe perder ninguna variable.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("tripbus.required-env", "DB_PASSWORD")
                .withProperty("DB_PASSWORD", "secreto");
        environment.getPropertySources().addLast(new MapPropertySource("application-qa.yml",
                Map.of("tripbus.required-env", "DB_PASSWORD,INTERNAL_API_TOKEN")));
        environment.setActiveProfiles("qa", "desplegado");

        assertThatThrownBy(() -> check.postProcessEnvironment(environment, application))
                .hasMessageContaining("[INTERNAL_API_TOKEN]");
    }
}
