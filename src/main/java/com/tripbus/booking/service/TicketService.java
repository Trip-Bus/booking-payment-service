package com.tripbus.booking.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.config.BookingProperties;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.PassengerResponse;
import com.tripbus.booking.web.dto.TicketResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** US-033: emisión del tiquete electrónico con código QR firmado. */
@Service
public class TicketService {

    private static final String PREFIX = "TRIPBUS1";

    private final TicketRepository ticketRepository;
    private final BookingRepository bookingRepository;
    private final PassengerRepository passengerRepository;
    private final CatalogClient catalogClient;
    private final byte[] secret;
    private final Clock clock;

    public TicketService(TicketRepository ticketRepository, BookingRepository bookingRepository,
                         PassengerRepository passengerRepository, CatalogClient catalogClient,
                         BookingProperties properties, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.bookingRepository = bookingRepository;
        this.passengerRepository = passengerRepository;
        this.catalogClient = catalogClient;
        this.secret = properties.ticketSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** Emite el tiquete (idempotente: si ya existe para la reserva, lo devuelve). Requiere transacción. */
    public Ticket issue(Booking booking) {
        return ticketRepository.findByBookingId(booking.getId()).orElseGet(() -> {
            UUID ticketId = UUID.randomUUID();
            String content = PREFIX + "|" + ticketId + "|" + booking.getId() + "|" + booking.getItineraryId() + "|"
                    + String.join("-", booking.getSeatNumbers());
            String signature = sign(content);
            return ticketRepository.save(new Ticket(ticketId, booking.getId(), content + "|" + signature, signature,
                    clock.instant()));
        });
    }

    @Transactional(readOnly = true)
    public TicketResponse get(UUID ticketId, UUID requesterId, boolean staff) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "El tiquete no existe"));
        Booking booking = bookingRepository.findById(ticket.getBookingId())
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "El tiquete no existe"));
        if (!staff && !booking.getUserId().equals(requesterId)) {
            throw ApiException.notFound("TICKET_NOT_FOUND", "El tiquete no existe");
        }
        var pasajeros = passengerRepository.findByBookingId(booking.getId()).stream()
                .map(p -> new PassengerResponse(p.getNombre(), p.getTipoDocumento(), p.getNumeroDocumento(),
                        p.getAsiento()))
                .toList();
        CatalogClient.ItinerarySummary it = catalogClient.itinerary(booking.getItineraryId());
        return new TicketResponse(ticket.getId(), booking.getId(), ticket.getEstado(), ticket.getEmitidoEn(),
                booking.getItineraryId(),
                it != null ? it.origen() : null,
                it != null ? it.destino() : null,
                it != null ? it.fechaSalida() : null,
                it != null ? it.empresa() : null,
                it != null ? it.placa() : null,
                booking.getSeatNumbers(), pasajeros, booking.getTotal(), booking.getMoneda(),
                ticket.getQrCode(), "data:image/png;base64," + Base64.getEncoder().encodeToString(qrPng(ticket.getQrCode())));
    }

    @Transactional(readOnly = true)
    public byte[] qrImage(UUID ticketId, UUID requesterId, boolean staff) {
        TicketResponse ticket = get(ticketId, requesterId, staff);
        return qrPng(ticket.qrCode());
    }

    /** Valida un QR al abordar: firma correcta y tiquete existente. */
    @Transactional(readOnly = true)
    public Map<String, Object> validate(String qrCode) {
        int last = qrCode.lastIndexOf('|');
        if (!qrCode.startsWith(PREFIX + "|") || last < 0) {
            return Map.of("valido", false, "motivo", "FORMATO_INVALIDO");
        }
        String content = qrCode.substring(0, last);
        String signature = qrCode.substring(last + 1);
        if (!MessageDigest.isEqual(sign(content).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return Map.of("valido", false, "motivo", "FIRMA_INVALIDA");
        }
        String[] parts = content.split("\\|");
        UUID ticketId = UUID.fromString(parts[1]);
        return ticketRepository.findById(ticketId)
                .<Map<String, Object>>map(t -> Map.of("valido", true, "ticketId", t.getId(), "bookingId",
                        t.getBookingId(), "itineraryId", parts[3], "asientos", parts[4], "estado", t.getEstado()))
                .orElse(Map.of("valido", false, "motivo", "TIQUETE_INEXISTENTE"));
    }

    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el tiquete", e);
        }
    }

    static byte[] qrPng(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 320, 320,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el código QR", e);
        }
    }
}
