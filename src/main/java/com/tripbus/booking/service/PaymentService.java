package com.tripbus.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.client.RemoteErrors;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.BookingStatus;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.PaymentStatus;
import com.tripbus.booking.domain.Refund;
import com.tripbus.booking.domain.Ticket;
import com.tripbus.booking.domain.WebhookLog;
import com.tripbus.booking.events.BookingEvents;
import com.tripbus.booking.payment.PaymentGateway;
import com.tripbus.booking.payment.WebhookSigner;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.RefundRepository;
import com.tripbus.booking.repository.WebhookLogRepository;
import com.tripbus.booking.web.dto.PayRequest;
import com.tripbus.booking.web.dto.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Saga de compra (pasos 4-11 del doc) con compensaciones:
 * <ol>
 *   <li>POST /bookings/{id}/pay → cobro en la pasarela (respuesta asíncrona por webhook).</li>
 *   <li>Webhook APROBADO → PaymentApproved → ConfirmSale en Seat Inventory → tiquete QR → TicketIssued.</li>
 *   <li>Webhook RECHAZADO → PaymentFailed → se libera la retención.</li>
 *   <li>Pago aprobado pero el asiento ya no se puede confirmar → reembolso + RefundProcessed.</li>
 * </ol>
 * Cada paso queda en saga_log; {@link SagaRecoveryJob} retoma las sagas interrumpidas.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final WebhookLogRepository webhookLogRepository;
    private final SeatInventoryClient seatInventory;
    private final PaymentGateway gateway;
    private final WebhookSigner signer;
    private final TicketService ticketService;
    private final TicketMailService ticketMail;
    private final RescheduleSaga rescheduleSaga;
    private final SagaLogger saga;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @SuppressWarnings("java:S107") // dependencias de la saga de compra, inyectadas por Spring
    public PaymentService(BookingRepository bookingRepository, PaymentRepository paymentRepository,
                          RefundRepository refundRepository, WebhookLogRepository webhookLogRepository,
                          SeatInventoryClient seatInventory, PaymentGateway gateway, WebhookSigner signer,
                          TicketService ticketService, TicketMailService ticketMail, RescheduleSaga rescheduleSaga,
                          SagaLogger saga, EventPublisher events, TransactionTemplate tx, ObjectMapper objectMapper,
                          Clock clock) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.webhookLogRepository = webhookLogRepository;
        this.seatInventory = seatInventory;
        this.gateway = gateway;
        this.signer = signer;
        this.ticketService = ticketService;
        this.ticketMail = ticketMail;
        this.rescheduleSaga = rescheduleSaga;
        this.saga = saga;
        this.events = events;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ pago

    /** US-032: inicia el pago. La idempotencia por Idempotency-Key la aplica IdempotencyService. */
    public PaymentResponse pay(UUID bookingId, UUID userId, PayRequest request, String idempotencyKey) {
        String method = request.method();
        validatePaymentData(request, method);
        Booking snapshot = bookingRepository.findById(bookingId)
                .filter(b -> b.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "La reserva no existe"));

        // Si ya hay un pago en curso o hecho, se informa ese mismo pago: nunca se cobra dos veces.
        PaymentResponse already = currentPaymentIfAny(snapshot);
        if (already != null) {
            return already;
        }
        if (snapshot.getEstado() != BookingStatus.PENDIENTE_PAGO) {
            throw ApiException.conflict("BOOKING_NOT_PAYABLE",
                    "La reserva no se puede pagar en estado " + snapshot.getEstado());
        }

        // Paso 2 del doc: la retención debe seguir viva antes de cobrar.
        SeatInventoryClient.Hold hold = seatInventory.getHold(snapshot.getHoldId());
        if (!"ACTIVA".equals(hold.estado()) || hold.segundosRestantes() <= 0) {
            tx.executeWithoutResult(s -> bookingRepository.lockById(bookingId).ifPresent(b -> {
                if (b.getEstado() == BookingStatus.PENDIENTE_PAGO) {
                    b.cambiarEstado(BookingStatus.EXPIRADA, clock.instant());
                    saga.log(b.getId(), "RETENCION_EXPIRADA", "OK", Map.of("holdId", b.getHoldId()));
                }
            }));
            throw ApiException.conflict("HOLD_EXPIRED", "La retención del asiento expiró; selecciónalo de nuevo");
        }

        // Pasos 5-6: registrar el pago (la BD impide dos pagos vivos para la misma reserva).
        Payment payment = tx.execute(s -> {
            Booking booking = bookingRepository.lockById(bookingId).orElseThrow();
            if (booking.getEstado() != BookingStatus.PENDIENTE_PAGO) {
                return null;
            }
            Instant now = clock.instant();
            Payment p = paymentRepository.save(new Payment(booking.getId(), booking.getTotal(),
                    userId + ":" + idempotencyKey, method, paymentLast4(request, method), request.cardBrand(),
                    "PSE".equals(method) ? bankName(request.pse().bancoCodigo()) : null, now));
            booking.cambiarEstado(BookingStatus.PAGO_EN_PROCESO, now);
            saga.log(booking.getId(), "PAGO_SOLICITADO", "OK", Map.of("paymentId", p.getId(), "monto", p.getMonto()));
            return p;
        });
        if (payment == null) {
            PaymentResponse current = currentPaymentIfAny(bookingRepository.findById(bookingId).orElseThrow());
            if (current != null) {
                return current;
            }
            throw ApiException.conflict("BOOKING_NOT_PAYABLE", "La reserva ya no se puede pagar");
        }

        // Paso 7: llamar a la pasarela. Si falla, compensación: liberar la retención.
        String redirectUrl = null;
        try {
            if ("PSE".equals(method) || "NEQUI".equals(method)) extendHoldQuietly(snapshot.getHoldId());
            String gatewayId;
            if ("TARJETA".equals(method)) {
                // Compatibilidad con los consumidores existentes de tarjeta durante la migración de la interfaz.
                gatewayId = gateway.charge(payment.getId(), payment.getMonto(), snapshot.getMoneda(), request.cardToken());
            } else {
                PaymentGateway.ChargeResult gatewayResult = gateway.charge(payment.getId(), payment.getMonto(), snapshot.getMoneda(),
                        new PaymentGateway.ChargeRequest(method, request.cardToken(),
                                request.pse() == null ? null : request.pse().bancoCodigo(),
                                request.nequi() == null ? null : request.nequi().telefono()));
                gatewayId = gatewayResult.gatewayPaymentId();
                redirectUrl = gatewayResult.redirectUrl();
            }
            tx.executeWithoutResult(s -> paymentRepository.lockById(payment.getId()).ifPresent(p -> {
                if (p.getPaymentGatewayId() == null) {
                    p.asignarGatewayId(gatewayId, clock.instant());
                }
                saga.log(bookingId, "COBRO_ENVIADO_A_PASARELA", "OK", Map.of("gatewayPaymentId", gatewayId));
            }));
        } catch (RuntimeException e) {
            log.error("La pasarela no aceptó el cobro de la reserva {}: {}", bookingId, e.getMessage());
            failPayment(payment.getId(), PASARELA_NO_DISPONIBLE);
            throw ApiException.unavailable("PAYMENT_GATEWAY_UNAVAILABLE",
                    "La pasarela de pagos no está disponible; no se realizó ningún cobro");
        }
        String message = "NEQUI".equals(method) ? "Acepta el pago en tu app Nequi" : "Pago en proceso. El resultado llegará en unos segundos.";
        return new PaymentResponse(payment.getId(), bookingId, PaymentStatus.PENDIENTE.name(), payment.getMonto(),
                snapshot.getMoneda(), message, redirectUrl);
    }

    private static void validatePaymentData(PayRequest request, String method) {
        if (!List.of("TARJETA", "PSE", "NEQUI").contains(method)) throw ApiException.badRequest("INVALID_PAYMENT_METHOD", "Método de pago no soportado");
        if ("TARJETA".equals(method) && (request.cardToken() == null || request.cardToken().isBlank())) throw ApiException.badRequest("INVALID_CARD", "El token de la tarjeta es obligatorio");
        if ("NEQUI".equals(method) && (request.nequi() == null || request.nequi().telefono() == null || !request.nequi().telefono().matches("3\\d{9}"))) throw ApiException.badRequest("INVALID_NEQUI_PHONE", "El celular Nequi debe tener 10 dígitos colombianos");
        if ("PSE".equals(method) && (request.pse() == null || !List.of("1", "2").contains(request.pse().bancoCodigo()) || !List.of("NATURAL", "JURIDICA").contains(request.pse().tipoPersona()) || request.pse().numeroDocumento() == null || request.pse().numeroDocumento().length() < 4)) throw ApiException.badRequest("INVALID_PSE", "Los datos PSE no son válidos");
    }
    private static String paymentLast4(PayRequest request, String method) {
        String value = "NEQUI".equals(method) ? request.nequi().telefono() : "PSE".equals(method) ? request.pse().numeroDocumento() : request.cardLast4();
        return value == null ? null : value.substring(Math.max(0, value.length() - 4));
    }
    private static String bankName(String code) { return "1".equals(code) ? "Banco que aprueba" : "Banco que rechaza"; }
    private void extendHoldQuietly(UUID holdId) { try { seatInventory.extendHold(holdId, 300); } catch (RuntimeException e) { log.info("No fue necesario extender la retención {}", holdId); } }

    private PaymentResponse currentPaymentIfAny(Booking booking) {
        if (booking.getEstado() == BookingStatus.PENDIENTE_PAGO) {
            return null;
        }
        List<Payment> payments = paymentRepository.findByBookingIdOrderByCreadoEnDesc(booking.getId());
        Payment live = payments.stream()
                .filter(p -> p.getEstado() == PaymentStatus.PENDIENTE || p.getEstado() == PaymentStatus.APROBADO)
                .findFirst().orElse(null);
        if (live == null) {
            return null;
        }
        return new PaymentResponse(live.getId(), booking.getId(), live.getEstado().name(), live.getMonto(),
                booking.getMoneda(), "Esta reserva ya tiene un pago " + live.getEstado().name().toLowerCase());
    }

    // ------------------------------------------------------------------ webhook

    public enum WebhookOutcome { APROBADO, RECHAZADO, DUPLICADO, IGNORADO, MONTO_INCORRECTO, APROBADO_TARDIO_REEMBOLSADO }

    /** Motivos con los que ESTE servicio da por fallido un pago sin respuesta de la pasarela. */
    static final String SIN_RESPUESTA = "SIN_RESPUESTA_DE_LA_PASARELA";
    static final String PASARELA_NO_DISPONIBLE = "PASARELA_NO_DISPONIBLE";
    static final String MONTO_INCORRECTO = "MONTO_INCORRECTO";
    /** US-150: la reserva original ya no está CONFIRMADA con su tiquete EMITIDO al confirmar la nueva. */
    static final String REPROGRAMACION_NO_APLICABLE = "REPROGRAMACION_NO_APLICABLE";

    private record WebhookResult(WebhookOutcome outcome, UUID bookingId) {
    }

    /** US-034: el webhook es idempotente y tolerante a reintentos de la pasarela. */
    public WebhookOutcome handleWebhook(String rawBody, String signature) {
        JsonNode event;
        try {
            event = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_WEBHOOK", "El cuerpo del webhook no es JSON válido");
        }
        String eventId = event.path("eventId").asText(null);
        if (!signer.verify(rawBody, signature)) {
            tx.executeWithoutResult(s -> webhookLogRepository.save(
                    new WebhookLog(eventId, rawBody, signature, false, "FIRMA_INVALIDA", clock.instant())));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Firma del webhook inválida");
        }

        String reference = event.path("reference").asText("");
        String gatewayId = event.path("gatewayPaymentId").asText(null);
        String status = event.path("status").asText("");
        String reason = event.path("reason").asText(null);

        WebhookResult result = tx.execute(s -> {
            UUID paymentId;
            try {
                paymentId = UUID.fromString(reference);
            } catch (IllegalArgumentException e) {
                return new WebhookResult(WebhookOutcome.IGNORADO, null);
            }
            Payment payment = paymentRepository.lockById(paymentId).orElse(null);
            boolean otroCobro = payment != null && payment.getPaymentGatewayId() != null && gatewayId != null
                    && !payment.getPaymentGatewayId().equals(gatewayId);
            WebhookOutcome outcome;
            UUID bookingId = null;
            if (payment == null || otroCobro) {
                outcome = WebhookOutcome.IGNORADO;
            } else if (esAprobadoTardio(payment, status)) {
                bookingId = payment.getBookingId();
                refundLateApproval(payment, gatewayId, montoDelEvento(event));
                outcome = WebhookOutcome.APROBADO_TARDIO_REEMBOLSADO;
            } else if (payment.getEstado() != PaymentStatus.PENDIENTE) {
                outcome = WebhookOutcome.DUPLICADO;
            } else {
                Instant now = clock.instant();
                Booking booking = bookingRepository.lockById(payment.getBookingId()).orElseThrow();
                bookingId = booking.getId();
                if (payment.getPaymentGatewayId() == null && gatewayId != null) {
                    payment.asignarGatewayId(gatewayId, now);
                }
                if ("APPROVED".equals(status) && !montoCoincide(event, payment, booking)) {
                    // US-032: nunca se aprueba un cobro por un monto o moneda distintos al de la reserva.
                    log.warn("Webhook {} del pago {}: monto/moneda {} {} no coinciden con {} {}; se rechaza",
                            eventId, payment.getId(), event.path("amount").asText(""),
                            event.path("currency").asText(""), payment.getMonto(), booking.getMoneda());
                    payment.rechazar(MONTO_INCORRECTO, now);
                    booking.cambiarEstado(BookingStatus.PAGO_RECHAZADO, now);
                    publishPaymentFailed(booking, MONTO_INCORRECTO);
                    outcome = WebhookOutcome.MONTO_INCORRECTO;
                } else if ("APPROVED".equals(status)) {
                    payment.aprobar(now);
                    booking.cambiarEstado(BookingStatus.PAGO_APROBADO, now);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("bookingId", booking.getId());
                    payload.put("paymentId", payment.getId());
                    payload.put("monto", payment.getMonto());
                    payload.put("timestamp", now);
                    saga.log(booking.getId(), "PAGO_APROBADO", "OK", payload);
                    events.publishAfterCommit(BookingEvents.PAYMENT_APPROVED, payload);
                    outcome = WebhookOutcome.APROBADO;
                } else {
                    String motivo = reason != null ? reason : "RECHAZADO_POR_PASARELA";
                    payment.rechazar(motivo, now);
                    booking.cambiarEstado(BookingStatus.PAGO_RECHAZADO, now);
                    publishPaymentFailed(booking, motivo);
                    outcome = WebhookOutcome.RECHAZADO;
                }
            }
            webhookLogRepository.save(new WebhookLog(eventId, rawBody, signature, true, outcome.name(), clock.instant()));
            return new WebhookResult(outcome, bookingId);
        });

        WebhookOutcome outcome = Objects.requireNonNull(result).outcome();
        UUID bookingId = result.bookingId();
        if (outcome == WebhookOutcome.APROBADO) {
            confirmAndIssue(bookingId);
        } else if (outcome == WebhookOutcome.RECHAZADO || outcome == WebhookOutcome.MONTO_INCORRECTO) {
            String motivo = outcome == WebhookOutcome.RECHAZADO ? "PAGO_RECHAZADO" : MONTO_INCORRECTO;
            bookingRepository.findById(bookingId).ifPresent(b -> releaseHoldQuietly(b.getHoldId(), motivo));
        }
        return outcome;
    }

    /**
     * US-035: la pasarela aprueba un cobro que este servicio ya había dado por fallido (sin respuesta a
     * los 5 min, o pasarela caída al cobrar). El asiento ya se liberó, así que el cobro se reembolsa.
     */
    private static boolean esAprobadoTardio(Payment payment, String status) {
        return "APPROVED".equals(status) && payment.getEstado() == PaymentStatus.RECHAZADO
                && (SIN_RESPUESTA.equals(payment.getMotivoRechazo())
                || PASARELA_NO_DISPONIBLE.equals(payment.getMotivoRechazo()));
    }

    /**
     * Reembolso de un aprobado tardío (dentro de la transacción del webhook, con el pago bloqueado).
     * Idempotente: el pago pasa a REEMBOLSADO, así que un segundo webhook igual llega como DUPLICADO.
     * Se reembolsa lo que la pasarela dice haber cobrado (el evento está firmado); si no lo trae, el monto del pago.
     */
    private void refundLateApproval(Payment payment, String gatewayId, BigDecimal montoCobrado) {
        if (refundRepository.findByPaymentId(payment.getId()).isPresent()) {
            return;
        }
        Instant now = clock.instant();
        if (payment.getPaymentGatewayId() == null && gatewayId != null) {
            payment.asignarGatewayId(gatewayId, now);
        }
        BigDecimal monto = montoCobrado != null ? montoCobrado : payment.getMonto();
        log.warn("Pago {} aprobado por la pasarela después de darlo por fallido ({}): se reembolsa {}",
                payment.getId(), payment.getMotivoRechazo(), monto);
        gateway.refund(payment.getPaymentGatewayId(), monto);
        Refund refund = refundRepository.save(new Refund(payment.getId(), monto, "APROBADO_TARDIO", now));
        payment.reembolsar(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", payment.getBookingId());
        payload.put("paymentId", payment.getId());
        payload.put("refundId", refund.getId());
        payload.put("monto", refund.getMonto());
        saga.log(payment.getBookingId(), "APROBADO_TARDIO_REEMBOLSADO", "OK", payload);
        events.publishAfterCommit(BookingEvents.REFUND_PROCESSED, payload);
    }

    /** El webhook trae exactamente el monto del pago y la moneda de la reserva. */
    static boolean montoCoincide(JsonNode event, Payment payment, Booking booking) {
        BigDecimal monto = montoDelEvento(event);
        String moneda = event.path("currency").asText(null);
        return monto != null && monto.compareTo(payment.getMonto()) == 0
                && moneda != null && moneda.equalsIgnoreCase(booking.getMoneda());
    }

    static BigDecimal montoDelEvento(JsonNode event) {
        JsonNode amount = event.path("amount");
        if (amount.isNumber()) {
            return amount.decimalValue();
        }
        if (amount.isTextual()) {
            try {
                return new BigDecimal(amount.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ continuación de la saga

    /**
     * Pasos 9-11: ConfirmSale en Seat Inventory y emisión del tiquete. Si Seat Inventory no
     * responde, la reserva queda en PAGO_APROBADO y el job de recuperación lo reintenta.
     * US-150: si la reserva es una reprogramación, en la misma transacción se retira el tiquete viejo
     * ({@link RescheduleSaga}) y después se anulan sus asientos. Si el tiquete viejo ya se usó para abordar,
     * la reserva nueva se reembolsa y sus asientos se devuelven.
     */
    public void confirmAndIssue(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || booking.getEstado() != BookingStatus.PAGO_APROBADO) {
            return;
        }
        boolean reprogramacion = booking.getOrigenBookingId() != null;
        if (reprogramacion && !rescheduleSaga.sigueVigente(booking)) {
            log.warn("La reserva {} ya no puede reemplazar a {}: se reembolsa", bookingId, booking.getOrigenBookingId());
            refundAndRelease(bookingId, REPROGRAMACION_NO_APLICABLE);
            return;
        }
        try {
            seatInventory.confirmSale(booking.getHoldId(), booking.getId());
        } catch (ApiException e) {
            log.warn("No se pudo confirmar el asiento de la reserva {} ({}): se reembolsa", bookingId, e.getCode());
            refundAndRelease(bookingId, "ASIENTO_NO_CONFIRMADO: " + e.getCode());
            return;
        } catch (RemoteErrors.RemoteUnavailableException e) {
            log.warn("Seat Inventory no disponible confirmando la reserva {}; se reintentará", bookingId);
            tx.executeWithoutResult(s -> saga.log(bookingId, "CONFIRMAR_ASIENTO", "REINTENTO",
                    Map.of("error", String.valueOf(e.getMessage()))));
            return;
        }

        Boolean confirmada = tx.execute(s -> emitirTiquete(bookingId, reprogramacion));
        if (reprogramacion) {
            terminarReprogramacion(booking, Boolean.TRUE.equals(confirmada));
        }
    }

    /**
     * Dentro de la transacción: tiquete emitido y reserva CONFIRMADA (en una reprogramación, además se retira el
     * tiquete viejo). Devuelve false si la reprogramación ya no aplica porque el tiquete viejo se usó para abordar.
     */
    private boolean emitirTiquete(UUID bookingId, boolean reprogramacion) {
        Booking b = bookingRepository.lockById(bookingId).orElseThrow();
        if (b.getEstado() != BookingStatus.PAGO_APROBADO) {
            return true;
        }
        saga.log(bookingId, "ASIENTO_VENDIDO", "OK", Map.of("holdId", b.getHoldId()));
        RescheduleSaga.Retiro retiro = reprogramacion ? rescheduleSaga.retirarAnterior(b) : null;
        if (reprogramacion && retiro == null) {
            return false;
        }
        Ticket ticket = ticketService.issue(b);
        b.cambiarEstado(BookingStatus.CONFIRMADA, clock.instant());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", b.getId());
        payload.put("ticketId", ticket.getId());
        payload.put("qrCode", ticket.getQrToken());
        payload.put("itineraryId", b.getItineraryId());
        payload.put("seatNumbers", b.getSeatNumbers());
        payload.put("userId", b.getUserId());
        payload.put("offerId", b.getOfferId());
        saga.log(bookingId, "TIQUETE_EMITIDO", "OK", Map.of("ticketId", ticket.getId()));
        events.publishAfterCommit(BookingEvents.TICKET_ISSUED, payload);
        if (retiro != null) {
            rescheduleSaga.registrar(b, retiro, ticket);
        }
        // US-033: correo con el QR en segundo plano tras el commit; si falla no toca la reserva ni el pago.
        ticketMail.sendAfterCommit(ticket.getId());
        return true;
    }

    /** US-150: tras confirmar se anulan los asientos viejos; si no aplicó, se devuelven los nuevos y se reembolsa. */
    private void terminarReprogramacion(Booking booking, boolean confirmada) {
        if (confirmada) {
            rescheduleSaga.anularAsientosAnteriores(booking.getOrigenBookingId());
            return;
        }
        log.warn("El tiquete anterior de la reserva {} ya se usó: se reembolsa la reprogramación", booking.getId());
        rescheduleSaga.devolverAsientosNuevos(booking);
        refundAndRelease(booking.getId(), REPROGRAMACION_NO_APLICABLE);
    }

    /** Compensación: el pago se aprobó pero el asiento se perdió → reembolso y liberación. */
    public void refundAndRelease(UUID bookingId, String motivo) {
        UUID holdId = tx.execute(s -> {
            Booking b = bookingRepository.lockById(bookingId).orElseThrow();
            if (b.getEstado() != BookingStatus.PAGO_APROBADO) {
                return null;
            }
            Payment payment = paymentRepository.findByBookingIdOrderByCreadoEnDesc(bookingId).stream()
                    .filter(p -> p.getEstado() == PaymentStatus.APROBADO).findFirst().orElse(null);
            Instant now = clock.instant();
            if (payment != null && refundRepository.findByPaymentId(payment.getId()).isEmpty()) {
                gateway.refund(payment.getPaymentGatewayId(), payment.getMonto());
                Refund refund = refundRepository.save(new Refund(payment.getId(), payment.getMonto(), motivo, now));
                payment.reembolsar(now);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("bookingId", bookingId);
                payload.put("refundId", refund.getId());
                payload.put("monto", refund.getMonto());
                saga.log(bookingId, "REEMBOLSO", "OK", payload);
                events.publishAfterCommit(BookingEvents.REFUND_PROCESSED, payload);
            }
            b.cambiarEstado(BookingStatus.REEMBOLSADA, now);
            return b.getHoldId();
        });
        if (holdId != null) {
            releaseHoldQuietly(holdId, "REEMBOLSO");
        }
    }

    /** Pago que nunca se completó en la pasarela: se marca rechazado y se libera la retención. */
    public void failPayment(UUID paymentId, String motivo) {
        UUID holdId = tx.execute(s -> {
            Payment payment = paymentRepository.lockById(paymentId).orElse(null);
            if (payment == null || payment.getEstado() != PaymentStatus.PENDIENTE) {
                return null;
            }
            Booking booking = bookingRepository.lockById(payment.getBookingId()).orElseThrow();
            Instant now = clock.instant();
            payment.rechazar(motivo, now);
            booking.cambiarEstado(BookingStatus.PAGO_RECHAZADO, now);
            publishPaymentFailed(booking, motivo);
            return booking.getHoldId();
        });
        if (holdId != null) {
            releaseHoldQuietly(holdId, motivo);
        }
    }

    private void publishPaymentFailed(Booking booking, String motivo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("holdId", booking.getHoldId());
        payload.put("motivo", motivo);
        saga.log(booking.getId(), "PAGO_FALLIDO", "COMPENSADO", payload);
        events.publishAfterCommit(BookingEvents.PAYMENT_FAILED, payload);
    }

    private void releaseHoldQuietly(UUID holdId, String motivo) {
        try {
            seatInventory.releaseHold(holdId, motivo);
        } catch (RuntimeException e) {
            // Seat Inventory también libera la retención al consumir PaymentFailed o al expirar.
            log.warn("No se pudo liberar la retención {}: {}", holdId, e.getMessage());
        }
    }
}
