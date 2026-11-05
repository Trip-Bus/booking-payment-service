package com.tripbus.booking.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Passenger;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.Refund;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-146: datos del comprobante cuando faltan piezas (sin empresa, sin NIT, catalog caído, sin desglose, sin cupón). */
class ReceiptServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-05T15:00:00Z");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PassengerRepository passengers = mock(PassengerRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReceiptService service = new ReceiptService(payments, refunds, bookings, passengers, catalog, jdbc);
    private final UUID duenio = UUID.randomUUID();
    private final BookingViewer viewer = new BookingViewer(duenio, false, false, null);

    @BeforeEach
    void numeros() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);
    }

    private Booking reserva(UUID companyId) {
        Booking b = new Booking(UUID.randomUUID(), duenio, UUID.randomUUID(), null, List.of("3"),
                new BigDecimal("90000"), "COP", AHORA, null, companyId, AHORA);
        b.asignarViaje(new Booking.Viaje("Bogotá", "Cali", AHORA, "Expreso Sur"));
        when(bookings.findById(b.getId())).thenReturn(Optional.of(b));
        when(passengers.findByBookingId(b.getId())).thenReturn(List.of(
                new Passenger(b.getId(), "Ana Pérez", "CC", "1020", "3")));
        return b;
    }

    private Payment aprobado(Booking b) {
        Payment p = Payment.efectivo(b.getId(), new BigDecimal("90000"), "k", AHORA);
        when(payments.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private static String texto(byte[] pdf) throws IOException {
        return new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
    }

    @Test
    void sinEmpresaSeUsaElNombreDeLaReservaYNoSeConsultaACatalog() throws Exception {
        Booking b = reserva(null);
        Payment p = aprobado(b);

        String t = texto(service.pago(p.getId(), viewer));

        assertThat(t).contains("TB-00000007").contains("Expreso Sur").doesNotContain("NIT").contains("EFECTIVO")
                .contains("Ana Pérez").contains("90000 COP").doesNotContain("Cupón");
        verify(catalog, never()).companyTerms(any(), any());
    }

    @Test
    void siCatalogNoRespondeOElNombreNoVieneSeUsaElDeLaReserva() throws Exception {
        Booking b = reserva(UUID.randomUUID());
        Payment p = aprobado(b);
        when(catalog.companyTerms(any(), any())).thenReturn(null);
        assertThat(texto(service.pago(p.getId(), viewer))).contains("Expreso Sur");

        when(catalog.companyTerms(any(), any())).thenReturn(new CatalogClient.CompanyTerms(b.getCompanyId(), null,
                "ACTIVA", BigDecimal.TEN, null, " "));
        assertThat(texto(service.pago(p.getId(), viewer))).contains("Expreso Sur").doesNotContain("NIT");
    }

    @Test
    void elComprobanteDeReembolsoLlevaElMotivo() throws Exception {
        Booking b = reserva(null);
        Payment p = aprobado(b);
        Refund r = Refund.de(p, new BigDecimal("90000"), "AFECTADA_POR_LA_EMPRESA", AHORA);
        when(refunds.findById(r.getId())).thenReturn(Optional.of(r));

        String t = texto(service.reembolso(r.getId(), viewer));

        assertThat(t).contains("Comprobante de reembolso").contains("AFECTADA_POR_LA_EMPRESA")
                .contains("Total reembolsado");
    }
}
