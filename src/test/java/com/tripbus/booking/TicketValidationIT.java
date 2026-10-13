package com.tripbus.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-033: el QR del tiquete solo se puede usar una vez al abordar. Contra PostgreSQL real (Testcontainers),
 * porque la garantía depende del UPDATE condicional en la BD, también con escaneos concurrentes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Testcontainers
class TicketValidationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BookingRepository bookings;
    @Autowired
    private TicketRepository tickets;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private TransactionTemplate tx;

    @MockitoBean
    private CatalogClient catalog;

    private final UUID conductorId = UUID.randomUUID();

    private Ticket emitirTiquete() {
        return tx.execute(s -> {
            Booking booking = bookings.save(new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    null, List.of("7"), new BigDecimal("95000"), "COP", Instant.now(), Instant.now()));
            return ticketService.issue(booking);
        });
    }

    private RequestPostProcessor conRol(UUID userId, String rol) {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("role", rol))
                .authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    private JsonNode validar(String qrCode) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("qrCode", qrCode));
        String response = mvc.perform(post("/tickets/validate").with(conRol(conductorId, "CONDUCTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void primerEscaneoEsValidoYElSegundoDiceYaUsado() throws Exception {
        Ticket ticket = emitirTiquete();

        JsonNode primero = validar(ticket.getQrCode());
        assertThat(primero.get("valido").asBoolean()).isTrue();
        assertThat(primero.get("estado").asText()).isEqualTo("USADO");
        assertThat(primero.get("usadoEn").isNull()).isFalse();

        JsonNode segundo = validar(ticket.getQrCode());
        assertThat(segundo.get("valido").asBoolean()).isFalse();
        assertThat(segundo.get("motivo").asText()).isEqualTo("YA_USADO");
        assertThat(segundo.get("usadoEn").asText()).isEqualTo(primero.get("usadoEn").asText());

        Ticket guardado = tickets.findById(ticket.getId()).orElseThrow();
        assertThat(guardado.getEstado()).isEqualTo("USADO");
        assertThat(guardado.getUsadoPor()).isEqualTo(conductorId);

        // GET /tickets/{id} refleja el uso.
        mvc.perform(get("/tickets/{id}", ticket.getId()).with(conRol(UUID.randomUUID(), "OPERADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("USADO"))
                .andExpect(jsonPath("$.usadoEn").value(primero.get("usadoEn").asText()));
    }

    @Test
    void diezEscaneosConcurrentesDelMismoQrSoloUnoEsValido() throws Exception {
        Ticket ticket = emitirTiquete();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<JsonNode>> escaneos = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            escaneos.add(pool.submit(() -> {
                salida.await();
                return validar(ticket.getQrCode());
            }));
        }
        salida.countDown();
        int validos = 0;
        int yaUsados = 0;
        for (Future<JsonNode> f : escaneos) {
            JsonNode r = f.get();
            if (r.get("valido").asBoolean()) {
                validos++;
            } else if ("YA_USADO".equals(r.get("motivo").asText())) {
                yaUsados++;
            }
        }
        pool.shutdown();
        assertThat(validos).isEqualTo(1);
        assertThat(yaUsados).isEqualTo(9);
    }

    @Test
    void firmaInvalidaYTiqueteInexistenteSiguenIgual() throws Exception {
        Ticket ticket = emitirTiquete();
        String qr = ticket.getQrCode();
        String falsificado = qr.substring(0, qr.lastIndexOf('|') + 1) + "x".repeat(32);
        assertThat(validar(falsificado).get("motivo").asText()).isEqualTo("FIRMA_INVALIDA");
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getEstado()).isEqualTo("EMITIDO");

        tickets.deleteById(ticket.getId());
        JsonNode r = validar(qr);
        assertThat(r.get("valido").asBoolean()).isFalse();
        assertThat(r.get("motivo").asText()).isEqualTo("TIQUETE_INEXISTENTE");
    }

    @Test
    void unPasajeroNoPuedeValidar() throws Exception {
        Ticket ticket = emitirTiquete();
        String body = objectMapper.writeValueAsString(java.util.Map.of("qrCode", ticket.getQrCode()));
        mvc.perform(post("/tickets/validate").with(conRol(UUID.randomUUID(), "PASAJERO"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getEstado()).isEqualTo("EMITIDO");
    }
}
