package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.Refund;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.RefundRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * US-146: comprobantes en PDF de un pago (GET /payments/{id}/receipt.pdf) y de un reembolso
 * (GET /refunds/{id}/receipt.pdf). Los ve el dueño de la reserva, el OPERADOR de su empresa y el ADMIN; a los demás
 * se responde 404, como si no existiera. El número es consecutivo (secuencia comprobantes_seq, compartida por pagos y
 * reembolsos) y se asigna la primera vez que se genera; luego siempre es el mismo.
 */
@Service
public class ReceiptService {

    static final String PREFIJO = "TB-";

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final BookingRepository bookingRepository;
    private final PassengerRepository passengerRepository;
    private final CatalogClient catalog;
    private final JdbcTemplate jdbc;

    public ReceiptService(PaymentRepository paymentRepository, RefundRepository refundRepository,
                          BookingRepository bookingRepository, PassengerRepository passengerRepository,
                          CatalogClient catalog, JdbcTemplate jdbc) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.bookingRepository = bookingRepository;
        this.passengerRepository = passengerRepository;
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    /** Comprobante de un pago aprobado (o que se aprobó y luego se reembolsó). */
    public byte[] pago(UUID paymentId, BookingViewer viewer) {
        Payment pago = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "El pago no existe"));
        Booking booking = visible(pago, viewer, "PAYMENT_NOT_FOUND", "El pago no existe");
        if (pago.getAprobadoEn() == null) {
            throw ApiException.conflict("RECEIPT_NOT_AVAILABLE", "El pago no está aprobado: no tiene comprobante");
        }
        String numero = numero("payments", paymentId);
        return ReceiptPdf.render(datos("Comprobante de pago", numero, pago.getAprobadoEn(), booking, pago,
                new Cierre("Total pagado", pago.getMonto(), null)));
    }

    /** Comprobante de un reembolso. */
    public byte[] reembolso(UUID refundId, BookingViewer viewer) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> ApiException.notFound("REFUND_NOT_FOUND", "El reembolso no existe"));
        Payment pago = paymentRepository.findById(refund.getPaymentId()).orElseThrow();
        Booking booking = visible(pago, viewer, "REFUND_NOT_FOUND", "El reembolso no existe");
        String numero = numero("refunds", refundId);
        return ReceiptPdf.render(datos("Comprobante de reembolso", numero, refund.getCreadoEn(), booking, pago,
                new Cierre("Total reembolsado", refund.getMonto(), refund.getMotivo())));
    }

    private Booking visible(Payment pago, BookingViewer viewer, String code, String message) {
        return bookingRepository.findById(pago.getBookingId()).filter(viewer::canSee)
                .orElseThrow(() -> ApiException.notFound(code, message));
    }

    /** Asigna el número la primera vez (UPDATE solo si aún no lo tiene) y lo devuelve con formato TB-00000001. */
    String numero(String tabla, UUID id) {
        // tabla es "payments" o "refunds" (constante interna, nunca viene de la petición).
        String sql = "payments".equals(tabla)
                ? "UPDATE payments SET comprobante_numero = nextval('comprobantes_seq') WHERE id = ? AND comprobante_numero IS NULL"
                : "UPDATE refunds SET comprobante_numero = nextval('comprobantes_seq') WHERE id = ? AND comprobante_numero IS NULL";
        String consulta = "payments".equals(tabla)
                ? "SELECT comprobante_numero FROM payments WHERE id = ?"
                : "SELECT comprobante_numero FROM refunds WHERE id = ?";
        jdbc.update(sql, id);
        Long numero = jdbc.queryForObject(consulta, Long.class, id);
        return PREFIJO + String.format(Locale.ROOT, "%08d", numero);
    }

    /** Cómo cierra el comprobante: etiqueta y valor del total, y el motivo (solo en un reembolso). */
    private record Cierre(String etiqueta, BigDecimal total, String motivo) {
    }

    private ReceiptPdf.Datos datos(String titulo, String numero, Instant fecha, Booking b, Payment pago, Cierre cierre) {
        CatalogClient.CompanyTerms empresa = b.getCompanyId() == null ? null
                : catalog.companyTerms(b.getCompanyId(), CommissionService.fechaDeVenta(fecha));
        String nombreEmpresa = empresa != null && empresa.nombre() != null ? empresa.nombre() : b.getEmpresaNombre();
        String nit = empresa != null ? empresa.nit() : null;
        List<String> asientos = b.getSeatNumbers();
        List<ReceiptPdf.Linea> pasajeros = passengerRepository.findByBookingId(b.getId()).stream()
                .sorted(Comparator.comparingInt((Passenger p) -> asientos.indexOf(p.getAsiento())))
                .map(p -> new ReceiptPdf.Linea(p.getNombre(), p.getTipoPasajero(), p.getAsiento(),
                        p.getCategoriaAsiento(), p.getPrecio() != null ? p.getPrecio() : b.getPrecioUnitario()))
                .toList();
        BigDecimal subtotal = b.getSubtotal() != null ? b.getSubtotal() : b.getTotal();
        boolean conCupon = b.getCuponCodigo() != null && b.getDescuentoCupon().signum() > 0;
        return new ReceiptPdf.Datos(titulo, numero, fecha, nombreEmpresa, nit, b.getId(), viaje(b), metodo(pago),
                pasajeros, subtotal, conCupon ? b.getCuponCodigo() : null, conCupon ? b.getDescuentoCupon() : null,
                cierre.etiqueta(), cierre.total(), b.getMoneda(), cierre.motivo());
    }

    /** "Bogotá → Medellín (sube en X, baja en Y)". */
    static String viaje(Booking b) {
        String viaje = b.getOrigen() != null && b.getDestino() != null ? b.getOrigen() + " → " + b.getDestino() : null;
        Booking.Tramo tramo = b.getTramo();
        if (tramo == null || tramo.paradaAbordaje() == null || tramo.paradaBajada() == null) {
            return viaje;
        }
        String detalle = "sube en " + tramo.paradaAbordaje() + ", baja en " + tramo.paradaBajada();
        return viaje == null ? detalle : viaje + " (" + detalle + ")";
    }

    /** Método con los últimos 4 dígitos (tarjeta o celular de Nequi) o el banco (PSE). */
    static String metodo(Payment pago) {
        StringBuilder sb = new StringBuilder(pago.getMetodo());
        if (pago.getTarjetaMarca() != null) {
            sb.append(' ').append(pago.getTarjetaMarca());
        }
        if (pago.getBanco() != null) {
            sb.append(' ').append(pago.getBanco());
        }
        String ultimos4 = pago.getMedioPagoUltimos4() != null ? pago.getMedioPagoUltimos4() : pago.getTarjetaUltimos4();
        if (ultimos4 != null) {
            sb.append(" **** ").append(ultimos4);
        }
        return sb.toString();
    }
}
