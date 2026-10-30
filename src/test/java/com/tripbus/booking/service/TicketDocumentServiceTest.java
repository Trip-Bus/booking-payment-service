package com.tripbus.booking.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-145: reenvío del tiquete (límite diario) y PDF, con los repositorios y el correo simulados. */
class TicketDocumentServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-30T15:00:00Z");
    private static final LocalDate HOY = LocalDate.of(2026, 10, 30);

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PassengerRepository passengers = mock(PassengerRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final TicketMailService mail = mock(TicketMailService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final UUID dueno = UUID.randomUUID();
    private final BookingViewer viewer = new BookingViewer(dueno, false, false, null);
    private TicketDocumentService service;

    @BeforeEach
    void preparar() {
        service = new TicketDocumentService(tickets, bookings, passengers, catalog, mail, tx,
                Clock.fixed(AHORA, ZoneOffset.UTC));
        when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(mail.enabled()).thenReturn(true);
    }

    private Ticket tiquete(String email, String estado, Booking.Viaje viaje) {
        Booking b = new Booking(UUID.randomUUID(), dueno, UUID.randomUUID(), null, List.of("12", "13"),
                new BigDecimal("50000"), "COP", AHORA, email, null, AHORA);
        b.asignarViaje(viaje);
        Ticket t = mock(Ticket.class);
        UUID id = UUID.randomUUID();
        when(t.getId()).thenReturn(id);
        when(t.getBookingId()).thenReturn(b.getId());
        when(t.getEstado()).thenReturn(estado);
        when(t.getQrCode()).thenReturn("TRIPBUS1|" + id + "|firma");
        when(tickets.findById(id)).thenReturn(Optional.of(t));
        when(bookings.findById(b.getId())).thenReturn(Optional.of(b));
        when(passengers.findByBookingId(b.getId())).thenReturn(List.of(
                new Passenger(b.getId(), "Luis", "CC", "2020", "13"), new Passenger(b.getId(), "Ana", "CC", "1010", "12")));
        return t;
    }

    private static void esperar(Runnable r, HttpStatus status, String codigo) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(codigo);
        });
    }

    @Test
    void reenviaYCuentaElReenvioDelDia() {
        Ticket t = tiquete("ana@mail.co", "USADO", null);
        when(tickets.registrarReenvio(t.getId(), HOY, 3)).thenReturn(1);
        when(tickets.reenviosDe(t.getId(), HOY)).thenReturn(2);

        assertThat(service.resend(t.getId(), viewer)).containsEntry("enviadoA", "a***@mail.co")
                .containsEntry("reenviosHoy", 2).containsEntry("maximoPorDia", 3);
        verify(mail).resend(t.getId());
    }

    @Test
    void pasadoElLimiteEs429YNoEnvia() {
        Ticket t = tiquete("ana@mail.co", "EMITIDO", null);
        when(tickets.registrarReenvio(eq(t.getId()), any(), anyInt())).thenReturn(0);
        UUID id = t.getId();

        esperar(() -> service.resend(id, viewer), HttpStatus.TOO_MANY_REQUESTS, "RESEND_LIMIT_REACHED");
        doReturn(null).when(tx).execute(any());
        esperar(() -> service.resend(id, viewer), HttpStatus.TOO_MANY_REQUESTS, "RESEND_LIMIT_REACHED");
        verify(mail, never()).resend(any());
    }

    @Test
    void sinCorreoTiqueteNoValidoOCorreoApagadoNoSeReenvia() {
        UUID sinCorreo = tiquete(null, "EMITIDO", null).getId();
        UUID anulado = tiquete("ana@mail.co", "ANULADO", null).getId();
        UUID valido = tiquete("ana@mail.co", "EMITIDO", null).getId();

        esperar(() -> service.resend(sinCorreo, viewer), HttpStatus.CONFLICT, "NO_CONTACT_EMAIL");
        esperar(() -> service.resend(anulado, viewer), HttpStatus.CONFLICT, "TICKET_NOT_RESENDABLE");
        when(mail.enabled()).thenReturn(false);
        esperar(() -> service.resend(valido, viewer), HttpStatus.SERVICE_UNAVAILABLE, "MAIL_UNAVAILABLE");
        verify(tickets, never()).registrarReenvio(any(), any(), anyInt());
    }

    @Test
    void elTiqueteAjenoOInexistenteNoExiste() {
        UUID id = tiquete("ana@mail.co", "EMITIDO", null).getId();
        BookingViewer otro = new BookingViewer(UUID.randomUUID(), false, false, null);
        UUID inexistente = UUID.randomUUID();

        esperar(() -> service.pdf(id, otro), HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        esperar(() -> service.resend(inexistente, viewer), HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
    }

    private static String texto(byte[] pdf) throws Exception {
        return new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
    }

    @Test
    void elPdfUsaLosDatosDeLaReservaYLaPlacaDeCatalog() throws Exception {
        Ticket t = tiquete("ana@mail.co", "EMITIDO", new Booking.Viaje("Cali", "Pasto", AHORA, "Bolivariano"));
        when(catalog.itinerary(any())).thenReturn(new CatalogClient.ItinerarySummary(null, "Otro", "Otro", null, null,
                "Otra", "XYZ987"));

        String texto = texto(service.pdf(t.getId(), viewer));

        assertThat(texto).contains(t.getId().toString(), "Cali", "Pasto", "Bolivariano", "XYZ987", "30/10/2026 10:00",
                "Ana", "Luis", "12, 13", "100000 COP").doesNotContain("Otra");
        assertThat(texto.indexOf("Ana")).isLessThan(texto.indexOf("Luis")); // en el orden de los asientos
    }

    @Test
    void sinDatosEnLaReservaUsaCatalogYSinCatalogQuedaSinDato() throws Exception {
        Ticket t = tiquete("ana@mail.co", "EMITIDO", null);
        when(catalog.itinerary(any())).thenReturn(new CatalogClient.ItinerarySummary(null, "Tunja", "Duitama", AHORA,
                null, "Libertadores", "AAA111"));
        assertThat(texto(service.pdf(t.getId(), viewer))).contains("Tunja", "Duitama", "Libertadores");

        when(catalog.itinerary(any())).thenReturn(null);
        assertThat(texto(service.pdf(t.getId(), viewer))).contains("Origen", "-").doesNotContain("Tunja");
    }
}
