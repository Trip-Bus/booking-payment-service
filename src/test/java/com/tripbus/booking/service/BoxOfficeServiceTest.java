package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.IdentityClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.TicketRepository;
import com.tripbus.booking.web.dto.BoxOfficeSaleRequest;
import com.tripbus.booking.web.dto.CashClosingResponse;
import com.tripbus.booking.web.dto.PassengerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-144: validaciones de la venta en taquilla, compensación y alcance del cierre de caja, con mocks. */
class BoxOfficeServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-30T15:00:00Z");

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final SeatInventoryClient seatInventory = mock(SeatInventoryClient.class);
    private final IdentityClient identity = mock(IdentityClient.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final UUID empresa = UUID.randomUUID();
    private final UUID itinerario = UUID.randomUUID();
    private final BookingViewer operador = new BookingViewer(UUID.randomUUID(), false, true, empresa);
    private BoxOfficeService service;

    @BeforeEach
    void preparar() {
        service = new BoxOfficeService(bookings, mock(PassengerRepository.class), payments, tickets, catalog,
                seatInventory, identity, paymentService, mock(SagaLogger.class), mock(EventPublisher.class), tx,
                Clock.fixed(AHORA, ZoneOffset.UTC));
        when(catalog.requireItinerary(itinerario)).thenReturn(new CatalogClient.ItinerarySummary(itinerario, "A", "B",
                AHORA.plus(Duration.ofHours(2)), null, "E", "P", empresa, "PROGRAMADO"));
        when(catalog.price(itinerario)).thenReturn(new CatalogClient.Price(itinerario, BigDecimal.TEN, "COP", "PROGRAMADO"));
        when(seatInventory.createHold(eq(itinerario), anyList(), any())).thenReturn(new SeatInventoryClient.Hold(
                UUID.randomUUID(), itinerario, List.of("1"), operador.userId(), null, null, "ACTIVA", AHORA, 600));
    }

    private BoxOfficeSaleRequest venta(List<String> asientos, int pasajeros, String email) {
        List<PassengerRequest> lista = java.util.stream.IntStream.range(0, pasajeros)
                .mapToObj(i -> new PassengerRequest("P" + i, "CC", "1000" + i)).toList();
        return new BoxOfficeSaleRequest(itinerario, asientos, lista,
                new BoxOfficeSaleRequest.Comprador("Juan", email, " "));
    }

    private void esperar(BoxOfficeSaleRequest request, BookingViewer quien, HttpStatus status, String codigo) {
        assertThatThrownBy(() -> service.sell(request, quien, "clave-0001"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(codigo);
                });
    }

    @Test
    void asientosRepetidosPasajerosQueNoCuadranUOperadorSinEmpresa() {
        esperar(venta(List.of("1", " 1"), 2, null), operador, HttpStatus.BAD_REQUEST, "DUPLICATE_SEATS");
        esperar(venta(List.of("1", "2"), 1, null), operador, HttpStatus.BAD_REQUEST, "PASSENGERS_MISMATCH");
        esperar(venta(List.of("1"), 1, null), new BookingViewer(UUID.randomUUID(), false, true, null),
                HttpStatus.FORBIDDEN, "OPERATOR_WITHOUT_COMPANY");
        verify(seatInventory, never()).createHold(any(), anyList(), any());
    }

    @Test
    void sinSalidaOSinPrecioVigenteNoSeVende() {
        when(catalog.requireItinerary(itinerario)).thenReturn(new CatalogClient.ItinerarySummary(itinerario, "A", "B",
                null, null, "E", "P", empresa, "PROGRAMADO"));
        esperar(venta(List.of("1"), 1, null), operador, HttpStatus.CONFLICT, "ITINERARY_DEPARTED");

        when(catalog.requireItinerary(itinerario)).thenReturn(new CatalogClient.ItinerarySummary(itinerario, "A", "B",
                AHORA.plus(Duration.ofHours(2)), null, "E", "P", empresa, null));
        when(catalog.price(itinerario)).thenReturn(new CatalogClient.Price(itinerario, BigDecimal.TEN, "COP", "CANCELADO"));
        esperar(venta(List.of("1"), 1, null), operador, HttpStatus.CONFLICT, "ITINERARY_NOT_AVAILABLE");
    }

    @Test
    void siNoSePuedeRegistrarLaVentaSeLiberaLaRetencion() {
        when(tx.execute(any())).thenThrow(new IllegalStateException("BD caída"));
        doThrow(new IllegalStateException("seat-inventory caído")).when(seatInventory).releaseHold(any(), anyString());
        when(identity.passengerIdByEmail("juan@mail.co")).thenReturn(java.util.Optional.empty());
        BoxOfficeSaleRequest request = venta(List.of("1"), 1, "Juan@Mail.co");

        assertThatThrownBy(() -> service.sell(request, operador, "clave-0001")).hasMessage("BD caída");
        verify(seatInventory).releaseHold(any(), eq("VENTA_TAQUILLA_FALLIDA"));
        verify(identity).passengerIdByEmail("juan@mail.co");
        verify(paymentService, never()).confirmAndIssue(any());
    }

    @Test
    void elCierreDeCajaSoloEsDelOperadorConEmpresaODelAdmin() {
        UUID otro = UUID.randomUUID();
        Booking mia = Booking.taquilla(new Booking.SeatSelection(UUID.randomUUID(), itinerario, null, List.of("1"), AHORA),
                operador.userId(), new Booking.VentaTaquilla(operador.userId(), "Juan", null, null), BigDecimal.TEN,
                "COP", empresa, AHORA);
        Booking ajena = Booking.taquilla(new Booking.SeatSelection(UUID.randomUUID(), itinerario, null, List.of("1"), AHORA),
                otro, new Booking.VentaTaquilla(otro, "Ana", null, null), BigDecimal.TEN, "COP", UUID.randomUUID(), AHORA);
        Booking sinPago = Booking.taquilla(new Booking.SeatSelection(UUID.randomUUID(), itinerario, null, List.of("1", "2"),
                AHORA), operador.userId(), new Booking.VentaTaquilla(operador.userId(), "Luis", null, null),
                BigDecimal.TEN, "COP", empresa, AHORA);
        when(bookings.findVentasTaquilla(any(), any())).thenReturn(List.of(mia, ajena, sinPago));
        Payment efectivo = Payment.efectivo(mia.getId(), BigDecimal.TEN, "k", AHORA);
        Payment devuelto = Payment.efectivo(ajena.getId(), BigDecimal.TEN, "k2", AHORA);
        devuelto.reembolsar(AHORA);
        when(payments.findByBookingIdOrderByCreadoEnDesc(mia.getId())).thenReturn(List.of(efectivo));
        when(payments.findByBookingIdOrderByCreadoEnDesc(ajena.getId())).thenReturn(List.of(devuelto));

        CashClosingResponse propio = service.cashClosing(operador, null, null);
        assertThat(propio.fecha()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(propio.cantidadVentas()).isEqualTo(2);
        assertThat(propio.cantidadAsientos()).isEqualTo(3);
        assertThat(propio.totalEfectivo()).isEqualByComparingTo("10");
        assertThat(propio.ventas().get(1).estadoPago()).isNull();
        assertThat(propio.ventas().get(1).ticketId()).isNull();

        CashClosingResponse admin = service.cashClosing(new BookingViewer(UUID.randomUUID(), true, false, null),
                LocalDate.of(2026, 10, 30), null);
        assertThat(admin.cantidadVentas()).isEqualTo(3);
        assertThat(admin.totalReembolsado()).isEqualByComparingTo("10");

        for (BookingViewer sinPermiso : List.of(new BookingViewer(UUID.randomUUID(), false, false, empresa),
                new BookingViewer(UUID.randomUUID(), false, true, null))) {
            assertThatThrownBy(() -> service.cashClosing(sinPermiso, null, null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
    }
}
