package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-033: validación del QR al abordar (uso único), con el repositorio simulado. */
class TicketServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-12T15:00:00Z");

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final UUID validador = UUID.randomUUID();
    private TicketService service;
    private Ticket ticket;

    @BeforeEach
    void emitirTiquete() {
        service = new TicketService(tickets, mock(BookingRepository.class), mock(PassengerRepository.class),
                mock(CatalogClient.class), new BookingProperties(null, null, null, null, "secreto-de-prueba"),
                Clock.fixed(AHORA, ZoneOffset.UTC));
        when(tickets.findByBookingId(any())).thenReturn(Optional.empty());
        when(tickets.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        Booking booking = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                List.of("12", "13"), new BigDecimal("95000"), "COP", AHORA, AHORA);
        ticket = service.issue(booking);
    }

    private static void poner(Ticket t, String campo, Object valor) throws Exception {
        Field f = Ticket.class.getDeclaredField(campo);
        f.setAccessible(true);
        f.set(t, valor);
    }

    @Test
    void primerEscaneoMarcaElTiqueteComoUsado() throws Exception {
        when(tickets.marcarUsado(ticket.getId(), AHORA, validador)).thenReturn(1);
        poner(ticket, "estado", "USADO");
        poner(ticket, "usadoEn", AHORA);
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        Map<String, Object> r = service.validate(ticket.getQrCode(), validador);

        assertThat(r).containsEntry("valido", true).containsEntry("estado", "USADO")
                .containsEntry("usadoEn", AHORA).containsEntry("ticketId", ticket.getId())
                .containsEntry("asientos", "12-13");
    }

    @Test
    void segundoEscaneoDiceYaUsadoConLaFechaDeUso() throws Exception {
        Instant antes = AHORA.minusSeconds(60);
        when(tickets.marcarUsado(ticket.getId(), AHORA, validador)).thenReturn(0);
        poner(ticket, "estado", "USADO");
        poner(ticket, "usadoEn", antes);
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        assertThat(service.validate(ticket.getQrCode(), validador))
                .containsExactlyInAnyOrderEntriesOf(Map.of("valido", false, "motivo", "YA_USADO", "usadoEn", antes));
    }

    @Test
    void tiqueteEnOtroEstadoNoEsValido() throws Exception {
        when(tickets.marcarUsado(ticket.getId(), AHORA, validador)).thenReturn(0);
        poner(ticket, "estado", "ANULADO");
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        assertThat(service.validate(ticket.getQrCode(), validador)).containsExactlyInAnyOrderEntriesOf(
                Map.of("valido", false, "motivo", "TIQUETE_NO_VALIDO", "estado", "ANULADO"));
    }

    @Test
    void tiqueteInexistente() {
        when(tickets.marcarUsado(ticket.getId(), AHORA, validador)).thenReturn(0);
        when(tickets.findById(ticket.getId())).thenReturn(Optional.empty());

        assertThat(service.validate(ticket.getQrCode(), validador))
                .containsExactlyInAnyOrderEntriesOf(Map.of("valido", false, "motivo", "TIQUETE_INEXISTENTE"));
    }

    @Test
    void firmaInvalidaYFormatoInvalidoNoTocanLaBd() {
        String qr = ticket.getQrCode();
        String falsificado = qr.substring(0, qr.lastIndexOf('|') + 1) + "x".repeat(32);

        assertThat(service.validate(falsificado, validador)).containsEntry("motivo", "FIRMA_INVALIDA");
        assertThat(service.validate("OTRO|cosa", validador)).containsEntry("motivo", "FORMATO_INVALIDO");
        verify(tickets, never()).marcarUsado(any(), any(), any());
    }
}
