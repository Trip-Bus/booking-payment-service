package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * US-145: reenvío del tiquete por correo (máximo {@link #MAX_REENVIOS_POR_DIA} por día, hora de Colombia) y tiquete
 * en PDF. Los pueden pedir el dueño, el OPERADOR de la empresa del viaje y el ADMIN; para cualquier otro el tiquete
 * no existe (404).
 */
@Service
public class TicketDocumentService {

    static final int MAX_REENVIOS_POR_DIA = 3;
    /** Un tiquete reemplazado o anulado ya no se reenvía: no sirve para abordar. */
    private static final Set<String> NO_REENVIABLES = Set.of(TicketService.REPROGRAMADO, TicketService.ANULADO);
    private static final String NO_EXISTE = "TICKET_NOT_FOUND";
    private static final String NO_EXISTE_MSG = "El tiquete no existe";

    private final TicketRepository ticketRepository;
    private final BookingRepository bookingRepository;
    private final PassengerRepository passengerRepository;
    private final CatalogClient catalog;
    private final TicketMailService ticketMail;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TicketDocumentService(TicketRepository ticketRepository, BookingRepository bookingRepository,
                                 PassengerRepository passengerRepository, CatalogClient catalog,
                                 TicketMailService ticketMail, TransactionTemplate tx, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.bookingRepository = bookingRepository;
        this.passengerRepository = passengerRepository;
        this.catalog = catalog;
        this.ticketMail = ticketMail;
        this.tx = tx;
        this.clock = clock;
    }

    private record Visible(Ticket ticket, Booking booking) {
    }

    private Visible visible(UUID ticketId, BookingViewer viewer) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> ApiException.notFound(NO_EXISTE, NO_EXISTE_MSG));
        Booking booking = bookingRepository.findById(ticket.getBookingId())
                .filter(viewer::canSee)
                .orElseThrow(() -> ApiException.notFound(NO_EXISTE, NO_EXISTE_MSG));
        return new Visible(ticket, booking);
    }

    /**
     * Reenvía el tiquete al correo de la reserva (en segundo plano). 409 si el tiquete ya no sirve o la reserva no
     * tiene correo, 503 si el correo está deshabilitado y 429 RESEND_LIMIT_REACHED si ya se reenvió 3 veces hoy.
     */
    public Map<String, Object> resend(UUID ticketId, BookingViewer viewer) {
        Visible v = visible(ticketId, viewer);
        if (NO_REENVIABLES.contains(v.ticket().getEstado())) {
            throw new ApiException(HttpStatus.CONFLICT, "TICKET_NOT_RESENDABLE", "Este tiquete ya no se puede reenviar",
                    Map.of("estado", v.ticket().getEstado()));
        }
        if (v.booking().getEmailContacto() == null) {
            throw ApiException.conflict("NO_CONTACT_EMAIL", "La reserva no tiene un correo al cual enviar el tiquete");
        }
        if (!ticketMail.enabled()) {
            throw ApiException.unavailable("MAIL_UNAVAILABLE", "El envío de correos no está disponible");
        }
        LocalDate hoy = clock.instant().atZone(TicketMailService.ZONA).toLocalDate();
        Integer registrados = tx.execute(s -> ticketRepository.registrarReenvio(ticketId, hoy, MAX_REENVIOS_POR_DIA));
        if (registrados == null || registrados == 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RESEND_LIMIT_REACHED",
                    "El tiquete ya se reenvió " + MAX_REENVIOS_POR_DIA + " veces hoy; intenta mañana",
                    Map.of("maximoPorDia", MAX_REENVIOS_POR_DIA));
        }
        ticketMail.resend(ticketId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", ticketId);
        body.put("enviadoA", TicketMailService.mask(v.booking().getEmailContacto()));
        body.put("reenviosHoy", ticketRepository.reenviosDe(ticketId, hoy));
        body.put("maximoPorDia", MAX_REENVIOS_POR_DIA);
        return body;
    }

    /** El tiquete en PDF. Los datos del viaje salen de la reserva y, si faltan, de catalog. */
    @Transactional(readOnly = true)
    public byte[] pdf(UUID ticketId, BookingViewer viewer) {
        Visible v = visible(ticketId, viewer);
        Booking b = v.booking();
        CatalogClient.ItinerarySummary it = catalog.itinerary(b.getItineraryId());
        var pasajeros = passengerRepository.findByBookingId(b.getId()).stream()
                .map(BookingService::toPassengerResponse)
                .sorted(Comparator.comparingInt(p -> b.getSeatNumbers().indexOf(p.asiento())))
                .toList();
        return TicketPdf.render(new TicketPdf.Datos(v.ticket().getId(), b.getId(), v.ticket().getEstado(),
                b.getOrigen() != null || it == null ? b.getOrigen() : it.origen(),
                b.getDestino() != null || it == null ? b.getDestino() : it.destino(),
                b.getFechaSalida() != null || it == null ? b.getFechaSalida() : it.fechaSalida(),
                b.getEmpresaNombre() != null || it == null ? b.getEmpresaNombre() : it.empresa(),
                it != null ? it.placa() : null,
                b.getSeatNumbers(), pasajeros, b.getTotal(), b.getMoneda(), v.ticket().getQrCode()));
    }
}
