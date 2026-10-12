package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.redis.LeaderLock;
import com.tripbus.booking.config.MailConfig;
import com.tripbus.booking.config.MailProperties;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** US-033: envío del tiquete por correo con repositorios y SMTP simulados. */
class TicketMailServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-12T15:00:00Z");

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PassengerRepository passengers = mock(PassengerRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final Clock clock = Clock.fixed(AHORA, ZoneOffset.UTC);

    private TicketMailService service;

    private TicketMailService servicio(String host) {
        service = new TicketMailService(tickets, bookings, passengers, catalog, mailSender,
                new MailProperties(host, null, null, null, null), clock);
        return service;
    }

    @AfterEach
    void apagar() {
        if (service != null) {
            service.shutdown();
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Booking reserva(String email) {
        return new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("12", "13"),
                new BigDecimal("95000"), "COP", AHORA, email, AHORA);
    }

    private Ticket tiquete(Booking booking) {
        Ticket ticket = new Ticket(UUID.randomUUID(), booking.getId(), "TRIPBUS1|x|y|z|12|firma", "firma", AHORA);
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        return ticket;
    }

    @Test
    void propiedadesPorDefecto() {
        MailProperties props = new MailProperties(" ", null, null, null, "");
        assertThat(props.enabled()).isFalse();
        assertThat(props.port()).isEqualTo(1025);
        assertThat(props.from()).isEqualTo("TripBus <no-reply@tripbus.co>");
        assertThat(new MailProperties("smtp.local", 2525, null, null, "x@y.co").port()).isEqualTo(2525);
    }

    @Test
    void mailConfigConYSinCredenciales() {
        JavaMailSenderImpl anonimo = (JavaMailSenderImpl) new MailConfig().ticketMailSender(
                new MailProperties("smtp.local", 2525, null, null, null));
        assertThat(anonimo.getHost()).isEqualTo("smtp.local");
        assertThat(anonimo.getPort()).isEqualTo(2525);
        assertThat(anonimo.getJavaMailProperties()).doesNotContainKey("mail.smtp.auth");

        JavaMailSenderImpl autenticado = (JavaMailSenderImpl) new MailConfig().ticketMailSender(
                new MailProperties("smtp.local", 587, "usuario", "clave", null));
        assertThat(autenticado.getUsername()).isEqualTo("usuario");
        assertThat(autenticado.getJavaMailProperties()).containsEntry("mail.smtp.auth", "true");
    }

    @Test
    void sinMailHostNoSeEnviaNada() {
        servicio("");
        UUID ticketId = UUID.randomUUID();
        assertThat(service.enabled()).isFalse();
        service.sendAfterCommit(ticketId);
        assertThat(service.send(ticketId)).isFalse();
        verifyNoInteractions(mailSender, tickets);

        TicketMailRetryJob job = new TicketMailRetryJob(tickets, service, mock(LeaderLock.class), clock);
        job.retry();
        assertThat(job.retryNow()).isZero();
        verifyNoInteractions(tickets);
    }

    @Test
    void enmascaraElCorreo() {
        assertThat(TicketMailService.mask("juan@mail.com")).isEqualTo("j***@mail.com");
        assertThat(TicketMailService.mask("sin-arroba")).isEqualTo("***");
        assertThat(TicketMailService.mask("@mail.com")).isEqualTo("***");
        assertThat(TicketMailService.mask(null)).isNull();
    }

    @Test
    void asuntoYCuerpoEnHoraDeColombiaYEscapados() {
        Booking booking = reserva("a@b.co");
        var viaje = new CatalogClient.ItinerarySummary(UUID.randomUUID(), "Bogotá", "Cali",
                Instant.parse("2026-12-20T01:30:00Z"), null, "Expreso", "ABC123");
        assertThat(TicketMailService.subject(viaje)).isEqualTo("Tu tiquete TripBus · Bogotá → Cali");
        String html = TicketMailService.html(viaje, booking, List.of("Ana <Pérez>", "Luis"));
        assertThat(html).contains("19/12/2026 20:30").contains("12, 13").contains("Ana &lt;Pérez&gt;, Luis")
                .contains("Expreso").contains("Bogotá → Cali");

        assertThat(TicketMailService.subject(null)).isEqualTo("Tu tiquete TripBus");
        assertThat(TicketMailService.subject(new CatalogClient.ItinerarySummary(null, "Bogotá", null, null, null,
                null, null))).isEqualTo("Tu tiquete TripBus");
        assertThat(TicketMailService.html(null, booking, List.of("Ana"))).doesNotContain("Salida").contains("Ana");
    }

    @Test
    void enviaYMarcaNotificado() {
        servicio("smtp.local");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        Ticket ticket = tiquete(reserva("ana@mail.com"));

        assertThat(service.send(ticket.getId())).isTrue();
        verify(mailSender).send(any(MimeMessage.class));
        verify(tickets).marcarNotificado(ticket.getId(), AHORA);
    }

    @Test
    void siElSmtpFallaSumaUnIntento() {
        servicio("smtp.local");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        doThrow(new MailSendException("caído")).when(mailSender).send(any(MimeMessage.class));
        Ticket ticket = tiquete(reserva("ana@mail.com"));

        assertThat(service.send(ticket.getId())).isFalse();
        verify(tickets).sumarIntentoNotificacion(ticket.getId());
        verify(tickets, never()).marcarNotificado(any(), any());
    }

    @Test
    void noEnviaSinTiqueteSinReservaOSinCorreo() {
        servicio("smtp.local");
        when(tickets.findById(any())).thenReturn(Optional.empty());
        assertThat(service.send(UUID.randomUUID())).isFalse();

        Ticket sinCorreo = tiquete(reserva(null));
        assertThat(service.send(sinCorreo.getId())).isFalse();

        Ticket sinReserva = new Ticket(UUID.randomUUID(), UUID.randomUUID(), "qr", "tok", AHORA);
        when(tickets.findById(sinReserva.getId())).thenReturn(Optional.of(sinReserva));
        when(bookings.findById(sinReserva.getBookingId())).thenReturn(Optional.empty());
        assertThat(service.send(sinReserva.getId())).isFalse();

        when(tickets.findById(any())).thenThrow(new IllegalStateException("BD caída"));
        assertThat(service.send(UUID.randomUUID())).isFalse();
        verifyNoInteractions(mailSender);
    }

    @Test
    void conTransaccionEnviaSoloDespuesDelCommit() {
        servicio("smtp.local");
        when(tickets.findById(any())).thenReturn(Optional.empty());
        UUID ticketId = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();
        service.sendAfterCommit(ticketId);
        verify(tickets, never()).findById(any());
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(tickets, timeout(2000)).findById(ticketId);
    }

    @Test
    void sinTransaccionEnviaDeInmediatoEnSegundoPlano() {
        servicio("smtp.local");
        when(tickets.findById(any())).thenReturn(Optional.empty());
        UUID ticketId = UUID.randomUUID();
        service.sendAfterCommit(ticketId);
        verify(tickets, timeout(2000)).findById(ticketId);
    }

    @Test
    void siNoSePuedeProgramarNoFalla() {
        assertThatCode(() -> TicketMailService.submit(task -> {
            throw new RejectedExecutionException("cola llena");
        }, () -> { })).doesNotThrowAnyException();
    }

    @Test
    void elJobReintentaLosPendientesYCuentaLosEnviados() {
        servicio("smtp.local");
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        Ticket ok = tiquete(reserva("ana@mail.com"));
        when(tickets.findIdsPendientesDeNotificar(5, AHORA.minus(TicketMailRetryJob.GRACIA_PRIMER_ENVIO)))
                .thenReturn(List.of(ok.getId(), UUID.randomUUID()));
        LeaderLock lock = mock(LeaderLock.class);
        TicketMailRetryJob job = new TicketMailRetryJob(tickets, service, lock, clock);

        assertThat(job.retryNow()).isEqualTo(1);
        job.retry();
        verify(lock).runIfLeader(any(), any(), any());
    }
}
