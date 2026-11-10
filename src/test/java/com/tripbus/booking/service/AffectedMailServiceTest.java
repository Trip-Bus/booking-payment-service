package com.tripbus.booking.service;

import com.tripbus.booking.config.MailProperties;
import com.tripbus.booking.domain.AffectedBooking;
import com.tripbus.booking.domain.Booking;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-154/US-155: avisos por correo de una reserva afectada (SMTP simulado). */
class AffectedMailServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-26T12:00:00Z");

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private AffectedMailService service;

    private AffectedMailService servicio(String host) {
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        service = new AffectedMailService(sender, new MailProperties(host, null, null, null, null));
        return service;
    }

    @AfterEach
    void apagar() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        if (service != null) {
            service.shutdown();
        }
    }

    private static Booking reserva(String email) {
        return new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of("1"), BigDecimal.TEN,
                "COP", AHORA, email, null, AHORA);
    }

    private static AffectedBooking afectada(AffectedBooking.Causa causa) {
        return new AffectedBooking(UUID.randomUUID(), UUID.randomUUID(), causa, AHORA.plusSeconds(86400), AHORA);
    }

    @Test
    void elAvisoDeAfectadaLlevaLoQuePasoYLasOpciones() throws Exception {
        servicio("smtp.local").notificarAfectada(reserva("ana@mail.co"), afectada(AffectedBooking.Causa.CANCELACION));

        ArgumentCaptor<MimeMessage> correo = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, timeout(3000)).send(correo.capture());
        assertThat(correo.getValue().getSubject()).isEqualTo("Tu viaje TripBus cambió: elige qué hacer");
        assertThat(correo.getValue().getAllRecipients()[0].toString()).isEqualTo("ana@mail.co");
        String html = String.valueOf(correo.getValue().getContent());
        assertThat(html).contains("La empresa canceló tu viaje.").contains("Reembolso del total pagado.")
                .contains("te reubicaremos");
    }

    @Test
    void elAvisoSeEnviaDespuesDelCommit() {
        TransactionSynchronizationManager.initSynchronization();
        servicio("smtp.local").notificarDecisionDelSistema(reserva("ana@mail.co"), afectada(AffectedBooking.Causa.RETRASO));
        verify(sender, never()).send(any(MimeMessage.class));

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(sender, timeout(3000)).send(any(MimeMessage.class));
    }

    @Test
    void sinSmtpOSinCorreoNoSeEnviaYUnFalloNoSePropaga() {
        servicio("").notificarAfectada(reserva("ana@mail.co"), afectada(AffectedBooking.Causa.RETRASO));
        servicio("smtp.local").notificarAfectada(reserva(null), afectada(AffectedBooking.Causa.RETRASO));
        verify(sender, never()).send(any(MimeMessage.class));

        doThrow(new MailSendException("caído")).when(sender).send(any(MimeMessage.class));
        service.enviar("ana@mail.co", "asunto", "<p>hola</p>");
        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void textosDeCadaCausaOpcionYDecision() {
        assertThat(AffectedMailService.causa(AffectedBooking.Causa.REPROGRAMACION)).contains("cambió el horario");
        assertThat(AffectedMailService.causa(AffectedBooking.Causa.RETRASO)).contains("retraso");
        assertThat(AffectedMailService.opcion(AffectedBooking.Opcion.ACEPTAR_CAMBIO)).contains("Aceptar");
        assertThat(AffectedMailService.opcion(AffectedBooking.Opcion.REPROGRAMAR)).contains("gratis");
        assertThat(AffectedMailService.porDefecto(AffectedBooking.Causa.RETRASO)).contains("aceptado");

        servicio("smtp.local");
        AffectedBooking reubicada = afectada(AffectedBooking.Causa.CANCELACION);
        reubicada.decidir(AffectedBooking.Estado.REUBICADA, AffectedBooking.DecididoPor.SISTEMA, UUID.randomUUID(), AHORA);
        AffectedBooking reembolsada = afectada(AffectedBooking.Causa.CANCELACION);
        reembolsada.decidir(AffectedBooking.Estado.REEMBOLSADA, AffectedBooking.DecididoPor.SISTEMA, null, AHORA);
        service.notificarDecisionDelSistema(reserva("ana@mail.co"), reubicada);
        service.notificarDecisionDelSistema(reserva("ana@mail.co"), reembolsada);
        verify(sender, timeout(3000).times(2)).send(any(MimeMessage.class));
    }
}
