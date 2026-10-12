package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.config.MailProperties;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * US-033: envía el tiquete (con el QR en PNG adjunto) al correo del comprador. El envío corre en segundo
 * plano después del commit que emite el tiquete; si falla, suma un intento y {@link TicketMailRetryJob}
 * lo reintenta. Un fallo del correo nunca toca el estado de la reserva ni del pago.
 */
@Service
public class TicketMailService {

    private static final Logger log = LoggerFactory.getLogger(TicketMailService.class);

    static final int MAX_INTENTOS = 5;
    static final ZoneId ZONA = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.of("es", "CO"));

    private final TicketRepository ticketRepository;
    private final BookingRepository bookingRepository;
    private final PassengerRepository passengerRepository;
    private final CatalogClient catalogClient;
    private final JavaMailSender mailSender;
    private final MailProperties properties;
    private final Clock clock;
    private final ThreadPoolTaskExecutor executor;

    public TicketMailService(TicketRepository ticketRepository, BookingRepository bookingRepository,
                             PassengerRepository passengerRepository, CatalogClient catalogClient,
                             JavaMailSender mailSender, MailProperties properties, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.bookingRepository = bookingRepository;
        this.passengerRepository = passengerRepository;
        this.catalogClient = catalogClient;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
        this.executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ticket-mail-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.initialize();
        if (!properties.enabled()) {
            log.warn("MAIL_HOST no está definido: el envío del tiquete por correo está deshabilitado");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * Programa el envío del tiquete para después del commit de la transacción actual (si no hay transacción,
     * lo programa de inmediato). Nunca lanza excepciones.
     */
    public void sendAfterCommit(UUID ticketId) {
        if (!enabled()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendAsync(ticketId);
                }
            });
        } else {
            sendAsync(ticketId);
        }
    }

    void sendAsync(UUID ticketId) {
        submit(executor, () -> send(ticketId));
    }

    static void submit(Executor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (RuntimeException e) {
            // Cola llena o apagándose: el job de reintento lo recoge después.
            log.warn("No se pudo programar el envío del tiquete por correo: {}", e.getMessage());
        }
    }

    /**
     * Envía el correo del tiquete si aún no se ha notificado. Devuelve true si quedó enviado. Si el SMTP falla
     * suma un intento; nunca lanza excepciones.
     */
    public boolean send(UUID ticketId) {
        if (!enabled()) {
            return false;
        }
        try {
            Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
            if (ticket == null || ticket.getNotificadoEn() != null) {
                return false;
            }
            Booking booking = bookingRepository.findById(ticket.getBookingId()).orElse(null);
            if (booking == null || booking.getEmailContacto() == null) {
                return false;
            }
            return deliver(ticket, booking);
        } catch (RuntimeException e) {
            log.error("Error inesperado enviando el tiquete {} por correo: {}", ticketId, e.getMessage());
            return false;
        }
    }

    private boolean deliver(Ticket ticket, Booking booking) {
        String destino = mask(booking.getEmailContacto());
        try {
            mailSender.send(buildMessage(ticket, booking));
        } catch (RuntimeException | MessagingException e) {
            ticketRepository.sumarIntentoNotificacion(ticket.getId());
            log.warn("No se pudo enviar el tiquete {} a {} (intento {} de {}): {}", ticket.getId(), destino,
                    ticket.getIntentosNotificacion() + 1, MAX_INTENTOS, e.getClass().getSimpleName());
            return false;
        }
        ticketRepository.marcarNotificado(ticket.getId(), clock.instant());
        log.info("Tiquete {} enviado por correo a {}", ticket.getId(), destino);
        return true;
    }

    private MimeMessage buildMessage(Ticket ticket, Booking booking) throws MessagingException {
        CatalogClient.ItinerarySummary viaje = catalogClient.itinerary(booking.getItineraryId());
        List<String> pasajeros = passengerRepository.findByBookingId(booking.getId()).stream()
                .map(Passenger::getNombre).toList();
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(properties.from());
        helper.setTo(booking.getEmailContacto());
        helper.setSubject(subject(viaje));
        helper.setText(html(viaje, booking, pasajeros), true);
        helper.addAttachment("tiquete-" + ticket.getId() + ".png",
                new ByteArrayResource(TicketService.qrPng(ticket.getQrCode())), "image/png");
        return message;
    }

    static String subject(CatalogClient.ItinerarySummary viaje) {
        if (viaje == null || viaje.origen() == null || viaje.destino() == null) {
            return "Tu tiquete TripBus";
        }
        return "Tu tiquete TripBus · " + viaje.origen() + " → " + viaje.destino();
    }

    static String html(CatalogClient.ItinerarySummary viaje, Booking booking, List<String> pasajeros) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style=\"font-family:Arial,sans-serif;color:#222\">")
                .append("<h2>¡Tu tiquete TripBus está listo!</h2>")
                .append("<p>Presenta el código QR adjunto al abordar. Es válido para un solo uso.</p>")
                .append("<table cellpadding=\"4\">");
        if (viaje != null && viaje.origen() != null && viaje.destino() != null) {
            fila(sb, "Viaje", viaje.origen() + " → " + viaje.destino());
        }
        if (viaje != null && viaje.fechaSalida() != null) {
            fila(sb, "Salida", FECHA.format(viaje.fechaSalida().atZone(ZONA)) + " (hora de Colombia)");
        }
        if (viaje != null && viaje.empresa() != null) {
            fila(sb, "Empresa", viaje.empresa());
        }
        fila(sb, "Asientos", String.join(", ", booking.getSeatNumbers()));
        fila(sb, "Pasajeros", String.join(", ", pasajeros));
        fila(sb, "Reserva", booking.getId().toString());
        sb.append("</table><p>¡Buen viaje!<br>Equipo TripBus</p></body></html>");
        return sb.toString();
    }

    private static void fila(StringBuilder sb, String etiqueta, String valor) {
        sb.append("<tr><td><strong>").append(etiqueta).append("</strong></td><td>")
                .append(HtmlUtils.htmlEscape(valor, "UTF-8")).append("</td></tr>");
    }

    /** Enmascara el correo para los logs: "juan@mail.com" → "j***@mail.com". */
    static String mask(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
