package com.tripbus.booking.service;

import com.tripbus.booking.config.MailProperties;
import com.tripbus.booking.domain.AffectedBooking;
import com.tripbus.booking.domain.Booking;
import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * US-154/US-155: correos al pasajero de una reserva afectada (qué pasó y qué puede elegir, y lo que decidió el
 * sistema). Mismo SMTP que el tiquete (US-033): sin MAIL_HOST no se envía. Se envía en segundo plano después del
 * commit y un fallo solo queda en el log: nunca cambia la reserva.
 */
@Service
public class AffectedMailService {

    private static final Logger log = LoggerFactory.getLogger(AffectedMailService.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.of("es", "CO"));

    private final JavaMailSender mailSender;
    private final MailProperties properties;
    private final ThreadPoolTaskExecutor executor;

    public AffectedMailService(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("affected-mail-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(500);
        executor.initialize();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** El viaje cambió: qué pasó, qué puede elegir y hasta cuándo. */
    public void notificarAfectada(Booking booking, AffectedBooking afectada) {
        String opciones = afectada.opciones().stream().map(AffectedMailService::opcion)
                .collect(Collectors.joining("</li><li>", "<ul><li>", "</li></ul>"));
        enviarDespuesDelCommit(booking, "Tu viaje TripBus cambió: elige qué hacer",
                "<p>" + causa(afectada.getCausa()) + "</p><p>Puedes elegir entre:</p>" + opciones
                        + "<p>Tienes hasta el " + FECHA.format(afectada.getPlazoDecision().atZone(TicketMailService.ZONA))
                        + " (hora de Colombia). Si no eliges, " + porDefecto(afectada.getCausa()) + ".</p>"
                        + "<p>Reserva: " + esc(booking.getId().toString()) + "</p>");
    }

    /** Lo que decidió el sistema al vencer el plazo (US-155). */
    public void notificarDecisionDelSistema(Booking booking, AffectedBooking afectada) {
        String texto = switch (afectada.getEstado()) {
            case REUBICADA -> "Te reubicamos sin costo en otro viaje de la misma ruta. Te enviamos el tiquete nuevo"
                    + " por correo (reserva " + esc(String.valueOf(afectada.getBookingNuevoId())) + ").";
            case REEMBOLSADA -> "No encontramos cupo en los 3 días siguientes, así que reembolsamos el total pagado.";
            default -> "Tu tiquete sigue siendo válido con el nuevo horario del viaje.";
        };
        enviarDespuesDelCommit(booking, "Actualización de tu reserva TripBus",
                "<p>Venció el plazo para elegir. " + texto + "</p><p>Reserva: " + esc(booking.getId().toString())
                        + "</p>");
    }

    static String causa(AffectedBooking.Causa causa) {
        return switch (causa) {
            case CANCELACION -> "La empresa canceló tu viaje.";
            case REPROGRAMACION -> "La empresa cambió el horario de tu viaje.";
            case RETRASO -> "Tu viaje sale con un retraso de una hora o más.";
        };
    }

    static String opcion(AffectedBooking.Opcion opcion) {
        return switch (opcion) {
            case ACEPTAR_CAMBIO -> "Aceptar el nuevo horario: tu tiquete sigue siendo válido.";
            case REPROGRAMAR -> "Reprogramar gratis a otro viaje con el mismo origen y destino.";
            case REEMBOLSO -> "Reembolso del total pagado.";
        };
    }

    static String porDefecto(AffectedBooking.Causa causa) {
        return causa == AffectedBooking.Causa.CANCELACION
                ? "te reubicaremos en el siguiente viaje con cupo de la misma ruta (o te reembolsaremos si no hay)"
                : "daremos por aceptado el nuevo horario";
    }

    private void enviarDespuesDelCommit(Booking booking, String asunto, String cuerpo) {
        if (!properties.enabled() || booking.getEmailContacto() == null) {
            return;
        }
        Runnable envio = () -> TicketMailService.submit(executor, () -> enviar(booking.getEmailContacto(), asunto, cuerpo));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    envio.run();
                }
            });
        } else {
            envio.run();
        }
    }

    void enviar(String destino, String asunto, String cuerpo) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(destino);
            helper.setSubject(asunto);
            helper.setText("<html><body style=\"font-family:Arial,sans-serif;color:#222\">" + cuerpo
                    + "<p>Equipo TripBus</p></body></html>", true);
            mailSender.send(message);
        } catch (RuntimeException | MessagingException e) {
            log.warn("No se pudo enviar el aviso de reserva afectada a {}: {}", TicketMailService.mask(destino),
                    e.getClass().getSimpleName());
        }
    }

    private static String esc(String valor) {
        return HtmlUtils.htmlEscape(valor, "UTF-8");
    }
}
