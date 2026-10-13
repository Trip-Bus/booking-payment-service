package com.tripbus.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.common.security.CurrentUser;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Payment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Validación de monto/moneda del webhook (US-032) y visibilidad de reservas por empresa (US-005). */
class WebhookMontoYAccesoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");
    private static final UUID EMPRESA = UUID.randomUUID();
    private static final UUID DUENO = UUID.randomUUID();

    private final Booking booking = new Booking(UUID.randomUUID(), DUENO, UUID.randomUUID(), null, List.of("12"),
            new BigDecimal("95000"), "COP", AHORA, null, EMPRESA, AHORA);
    private final Payment payment = new Payment(booking.getId(), new BigDecimal("95000.00"), "k", "4242", "VISA", AHORA);

    private static JsonNode evento(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"amount\":95000,\"currency\":\"COP\"}           | true",
            "{\"amount\":\"95000.0\",\"currency\":\"cop\"}     | true",
            "{\"amount\":1000,\"currency\":\"COP\"}            | false",
            "{\"amount\":95000,\"currency\":\"USD\"}           | false",
            "{\"amount\":95000}                                | false",
            "{\"currency\":\"COP\"}                            | false",
            "{\"amount\":\"no-es-numero\",\"currency\":\"COP\"} | false",
            "{\"amount\":true,\"currency\":\"COP\"}            | false"})
    void montoYMonedaDebenCoincidir(String json, boolean esperado) throws Exception {
        assertThat(PaymentService.montoCoincide(evento(json), payment, booking)).isEqualTo(esperado);
    }

    @Test
    void montoDelEventoAceptaNumeroOTexto() throws Exception {
        assertThat(PaymentService.montoDelEvento(evento("{\"amount\":1500.5}"))).isEqualByComparingTo("1500.5");
        assertThat(PaymentService.montoDelEvento(evento("{\"amount\":\" 20 \"}"))).isEqualByComparingTo("20");
        assertThat(PaymentService.montoDelEvento(evento("{}"))).isNull();
    }

    private static JwtAuthenticationToken token(UUID subject, String rol, String companyId) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject.toString()).claim("role", rol);
        if (companyId != null) {
            jwt.claim("companyId", companyId);
        }
        return new JwtAuthenticationToken(jwt.build(), List.of(new SimpleGrantedAuthority("ROLE_" + rol)));
    }

    @Test
    void elDuenoVeSuReservaYOtroPasajeroNo() {
        assertThat(BookingViewer.of(token(DUENO, "PASAJERO", null)).canSee(booking)).isTrue();
        assertThat(BookingViewer.of(token(UUID.randomUUID(), "PASAJERO", null)).canSee(booking)).isFalse();
    }

    @Test
    void adminVeTodas() {
        assertThat(BookingViewer.of(token(UUID.randomUUID(), "ADMIN", null)).canSee(booking)).isTrue();
    }

    @Test
    void operadorSoloVeLasDeSuEmpresa() {
        assertThat(BookingViewer.of(token(UUID.randomUUID(), "OPERADOR", EMPRESA.toString())).canSee(booking)).isTrue();
        assertThat(BookingViewer.of(token(UUID.randomUUID(), "OPERADOR", UUID.randomUUID().toString()))
                .canSee(booking)).isFalse();
        assertThat(BookingViewer.of(token(UUID.randomUUID(), "OPERADOR", null)).canSee(booking)).isFalse();
        // Un claim mal formado no da acceso (ni rompe la petición con un 500).
        assertThat(BookingViewer.of(token(UUID.randomUUID(), "OPERADOR", "no-es-uuid")).canSee(booking)).isFalse();
        assertThat(CurrentUser.companyId(token(UUID.randomUUID(), "OPERADOR", " "))).isNull();
    }

    @Test
    void reservaSinEmpresaNoLaVeNingunOperador() {
        Booking antigua = new Booking(UUID.randomUUID(), DUENO, UUID.randomUUID(), null, List.of("1"),
                BigDecimal.TEN, "COP", AHORA, AHORA);

        assertThat(antigua.getCompanyId()).isNull();
        assertThat(BookingViewer.of(token(UUID.randomUUID(), "OPERADOR", EMPRESA.toString())).canSee(antigua)).isFalse();
        assertThat(BookingViewer.of(token(DUENO, "PASAJERO", null)).canSee(antigua)).isTrue();
    }
}
