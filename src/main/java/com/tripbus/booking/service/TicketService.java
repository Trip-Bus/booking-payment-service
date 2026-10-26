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
import java.util.Optional;
import java.util.UUID;

/** US-033: emisión del tiquete electrónico con código QR firmado. */
@Service
public class TicketService {

    private static final String PREFIX = "TRIPBUS1";
    static final String EMITIDO = "EMITIDO";
    static final String USADO = "USADO";
    static final String REPROGRAMADO = "REPROGRAMADO";
    static final String ANULADO = "ANULADO";
    private static final String VALIDO = "valido";
    private static final String MOTIVO = "motivo";
    private static final String ESTADO = "estado";
    private static final String USADO_EN = "usadoEn";

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
            String content = content(ticketId, booking);
            String signature = sign(content);
            return ticketRepository.save(new Ticket(ticketId, booking.getId(), content + "|" + signature, signature,
                    clock.instant()));
        });
    }

    /**
     * US-139: el QR incluye los asientos (no el nombre ni el documento del pasajero), así que un cambio de asiento
     * exige uno nuevo. QR nuevo con los datos actuales de la reserva; el anterior deja de validar porque validate exige el
     * qr_token vigente. El contenido lleva un sufijo aleatorio para que nunca se repita un QR ya entregado (p. ej.
     * al volver al asiento original). Devuelve false si el tiquete ya no está EMITIDO. Requiere transacción.
     */
    public boolean reissueQr(UUID ticketId, Booking booking) {
        String content = content(ticketId, booking) + "|R" + UUID.randomUUID().toString().substring(0, 8);
        String signature = sign(content);
        return ticketRepository.reemplazarQr(ticketId, content + "|" + signature, signature) == 1;
    }

    private static String content(UUID ticketId, Booking booking) {
        return PREFIX + "|" + ticketId + "|" + booking.getId() + "|" + booking.getItineraryId() + "|"
                + String.join("-", booking.getSeatNumbers());
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
                .map(BookingService::toPassengerResponse)
                .toList();
        CatalogClient.ItinerarySummary it = catalogClient.itinerary(booking.getItineraryId());
        return new TicketResponse(ticket.getId(), booking.getId(), ticket.getEstado(), ticket.getEmitidoEn(),
                ticket.getUsadoEn(),
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

    /**
     * Valida un QR al abordar: formato, firma y tiquete existente. Si es válido lo marca como USADO con un
     * UPDATE condicional atómico, así el mismo QR no sirve para abordar dos veces aunque se escanee en paralelo.
     * Un tiquete REPROGRAMADO responde 409 TICKET_RESCHEDULED y uno ANULADO 409 TICKET_VOIDED; un QR
     * reemplazado por un cambio de asiento (US-139) responde motivo QR_REEMPLAZADO.
     */
    @Transactional
    public Map<String, Object> validate(String qrCode, UUID validadorId) {
        int last = qrCode.lastIndexOf('|');
        if (!qrCode.startsWith(PREFIX + "|") || last < 0) {
            return Map.of(VALIDO, false, MOTIVO, "FORMATO_INVALIDO");
        }
        String content = qrCode.substring(0, last);
        String signature = qrCode.substring(last + 1);
        if (!MessageDigest.isEqual(sign(content).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return Map.of(VALIDO, false, MOTIVO, "FIRMA_INVALIDA");
        }
        String[] parts = content.split("\\|");
        UUID ticketId = UUID.fromString(parts[1]);
        int marcados = ticketRepository.marcarUsado(ticketId, signature, clock.instant(), validadorId);
        // Se relee tras el UPDATE (el contexto quedó limpio) para responder con lo que quedó en la BD.
        Optional<Ticket> ticket = ticketRepository.findById(ticketId);
        if (ticket.isEmpty()) {
            return Map.of(VALIDO, false, MOTIVO, "TIQUETE_INEXISTENTE");
        }
        Ticket t = ticket.get();
        if (marcados == 1) {
            return Map.of(VALIDO, true, "ticketId", t.getId(), "bookingId", t.getBookingId(),
                    "itineraryId", parts[3], "asientos", parts[4], ESTADO, t.getEstado(), USADO_EN, t.getUsadoEn());
        }
        return switch (t.getEstado()) {
            case USADO -> Map.of(VALIDO, false, MOTIVO, "YA_USADO", USADO_EN, t.getUsadoEn());
            case REPROGRAMADO -> throw ApiException.conflict("TICKET_RESCHEDULED",
                    "El tiquete se reprogramó a otro viaje; presente el tiquete nuevo");
            case ANULADO -> throw ApiException.conflict("TICKET_VOIDED", "El tiquete fue anulado");
            case EMITIDO -> Map.of(VALIDO, false, MOTIVO, "QR_REEMPLAZADO");
            default -> Map.of(VALIDO, false, MOTIVO, "TIQUETE_NO_VALIDO", ESTADO, t.getEstado());
        };
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
