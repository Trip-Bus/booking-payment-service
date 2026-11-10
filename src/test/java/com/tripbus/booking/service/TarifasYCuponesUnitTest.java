package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.domain.Booking;
import com.tripbus.booking.domain.Coupon;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.repository.CouponRedemptionRepository;
import com.tripbus.booking.repository.CouponRepository;
import com.tripbus.booking.repository.ManifestRow;
import com.tripbus.booking.web.dto.CouponRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sprint 6: reglas de la tarifa, del cupón, del comprobante, del tramo y del manifiesto, sin BD. */
class TarifasYCuponesUnitTest {

    private static final Instant AHORA = Instant.parse("2026-11-05T15:00:00Z");

    private final CatalogClient catalog = mock(CatalogClient.class);
    private final FlashOffersClient flashOffers = mock(FlashOffersClient.class);
    private final TarifaService tarifas = new TarifaService(catalog, flashOffers);
    private final UUID usuario = UUID.randomUUID();

    private static SeatInventoryClient.Hold hold(UUID offerId, Integer desde, Integer hasta, String... asientos) {
        return new SeatInventoryClient.Hold(UUID.randomUUID(), UUID.randomUUID(), List.of(asientos), UUID.randomUUID(),
                offerId, null, "ACTIVA", AHORA, 600, desde, hasta);
    }

    private void catalogCotiza(String estado, String moneda) {
        when(catalog.pricing(any())).thenAnswer(inv -> {
            CatalogClient.PricingRequest r = inv.getArgument(0);
            return new CatalogClient.PricingResult(r.itineraryId(), estado, moneda,
                    new CatalogClient.Tramo(1, "Bogotá", 4, "Ibagué"), r.pasajeros().stream()
                    .map(p -> new CatalogClient.PassengerPrice(p.asiento(), p.tipoPasajero(), "ESTANDAR",
                            BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("9"))).toList());
        });
    }

    // ------------------------------------------------------------------ tarifas

    @Test
    void tiposDePasajero() {
        assertThat(TarifaService.tipo(null)).isEqualTo(TarifaService.ADULTO);
        assertThat(TarifaService.tipo(" ")).isEqualTo(TarifaService.ADULTO);
        assertThat(TarifaService.tipo(" nino ")).isEqualTo(TarifaService.NINO);
        assertThat(TarifaService.tipo("adulto_mayor")).isEqualTo(TarifaService.ADULTO_MAYOR);
        assertThatThrownBy(() -> TarifaService.tipo("BEBE")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("INVALID_PASSENGER_TYPE"));
    }

    @Test
    void laTarifaDeCatalogSumaElDesgloseYSoloGuardaElTramoSiSeCompraUno() {
        catalogCotiza("PROGRAMADO", null);

        TarifaService.Tarifa completa = tarifas.calcular(hold(null, null, null, "1", "2"), List.of("NINO"), usuario);
        assertThat(completa.subtotal()).isEqualByComparingTo("18");
        assertThat(completa.moneda()).isEqualTo("COP");
        assertThat(completa.tramo()).isNull();
        assertThat(completa.deOferta()).isFalse();
        assertThat(completa.pasajeros()).extracting(TarifaService.PrecioPasajero::tipoPasajero)
                .containsExactly("NINO", "ADULTO");

        TarifaService.Tarifa tramo = tarifas.calcular(hold(null, 1, 4, "3"), null, usuario);
        assertThat(tramo.tramo()).isEqualTo(new Booking.Tramo(1, "Bogotá", 4, "Ibagué"));

        TarifaService.Tarifa taquilla = tarifas.calcular(UUID.randomUUID(), List.of("7"), List.of("ADULTO"));
        assertThat(taquilla.tramo()).isNull();
        assertThat(taquilla.subtotal()).isEqualByComparingTo("9");
    }

    @Test
    void unViajeQueYaNoEstaProgramadoNoSeVende() {
        catalogCotiza("CANCELADO", "COP");
        SeatInventoryClient.Hold h = hold(null, null, null, "1");
        List<String> tipos = List.of("ADULTO");

        assertThatThrownBy(() -> tarifas.calcular(h, tipos, usuario)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("ITINERARY_NOT_AVAILABLE"));
    }

    @Test
    void unaRetencionDeOfertaConservaElPrecioDeLaOferta() {
        UUID oferta = UUID.randomUUID();
        SeatInventoryClient.Hold h = hold(oferta, 2, 3, "4", "5");
        when(flashOffers.reservationByHold(org.mockito.ArgumentMatchers.eq(oferta), any())).thenReturn(new FlashOffersClient.OfferReservation(
                UUID.randomUUID(), oferta, h.holdId(), usuario, "4", new BigDecimal("49900"), "ACTIVA"));

        TarifaService.Tarifa t = tarifas.calcular(h, List.of("NINO", "ADULTO"), usuario);

        assertThat(t.deOferta()).isTrue();
        assertThat(t.subtotal()).isEqualByComparingTo("99800");
        assertThat(t.tramo()).isEqualTo(new Booking.Tramo(2, null, 3, null));
        assertThat(tarifas.calcular(hold(oferta, null, null, "6"), null, usuario).tramo()).isNull();
        verify(catalog, never()).pricing(any());
        List<String> tipos = List.of("ADULTO");
        UUID otro = UUID.randomUUID();
        assertThatThrownBy(() -> tarifas.calcular(h, tipos, otro)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ------------------------------------------------------------------ cupones

    private static Coupon.Datos datos(Coupon.Tipo tipo, String valor, List<UUID> rutas) {
        return new Coupon.Datos("PROMO10", tipo, new BigDecimal(valor), null, AHORA, AHORA.plus(Duration.ofDays(1)),
                null, null, null, rutas, true);
    }

    @Test
    void descuentoVigenciaYRutasDelCupon() {
        Coupon porcentaje = new Coupon(datos(Coupon.Tipo.PORCENTAJE, "12.5", null), UUID.randomUUID(), AHORA);
        assertThat(porcentaje.descuentoPara(new BigDecimal("95555"))).isEqualByComparingTo("11944");
        assertThat(porcentaje.vigenteEn(AHORA)).isTrue();
        assertThat(porcentaje.vigenteEn(AHORA.minusSeconds(1))).isFalse();
        assertThat(porcentaje.vigenteEn(AHORA.plus(Duration.ofDays(1)))).isFalse();
        assertThat(porcentaje.aplicaARuta(null)).isTrue();

        Coupon valor = new Coupon(datos(Coupon.Tipo.VALOR, "50000", List.of()), UUID.randomUUID(), AHORA);
        assertThat(valor.descuentoPara(new BigDecimal("30000"))).isEqualByComparingTo("30000");
        assertThat(valor.descuentoPara(new BigDecimal("80000"))).isEqualByComparingTo("50000");
        assertThat(valor.getRouteIds()).isEmpty();

        UUID ruta = UUID.randomUUID();
        Coupon deRuta = new Coupon(datos(Coupon.Tipo.VALOR, "1", List.of(ruta)), UUID.randomUUID(), AHORA);
        assertThat(deRuta.aplicaARuta(ruta)).isTrue();
        assertThat(deRuta.aplicaARuta(UUID.randomUUID())).isFalse();
        assertThat(deRuta.aplicaARuta(null)).isFalse();
        deRuta.desactivar(AHORA);
        assertThat(deRuta.isActivo()).isFalse();
    }

    @Test
    void validacionesDelCuponAlCrearlo() {
        CouponRequest porcentajeAlto = new CouponRequest("X1X", "PORCENTAJE", new BigDecimal("101"), null, AHORA,
                AHORA.plusSeconds(60), null, null, null, null, null);
        assertThatThrownBy(() -> CouponService.datos(porcentajeAlto, null)).isInstanceOf(ApiException.class);
        CouponRequest alReves = new CouponRequest("X1X", "VALOR", BigDecimal.ONE, null, AHORA, AHORA, null, null,
                null, null, null);
        assertThatThrownBy(() -> CouponService.datos(alReves, null)).isInstanceOf(ApiException.class);
        CouponRequest inactivo = new CouponRequest(" X1X ", "VALOR", BigDecimal.ONE, null, AHORA,
                AHORA.plusSeconds(60), null, null, null, null, false);
        assertThat(CouponService.datos(inactivo, null).activo()).isFalse();
        assertThat(CouponService.datos(inactivo, null).codigo()).isEqualTo("X1X");
    }

    @Test
    void siOtraCompraSeLlevaElUltimoUsoLaReservaNoSeCrea() {
        CouponRepository coupons = mock(CouponRepository.class);
        CouponRedemptionRepository redemptions = mock(CouponRedemptionRepository.class);
        CouponService service = new CouponService(coupons, redemptions, Clock.fixed(AHORA, ZoneOffset.UTC));
        UUID couponId = UUID.randomUUID();
        when(coupons.sumarUso(any(), any())).thenReturn(0);
        CouponService.Descuento descuento = new CouponService.Descuento(couponId, "X", BigDecimal.TEN);
        UUID reserva = UUID.randomUUID();

        assertThatThrownBy(() -> service.redimir(descuento, reserva, usuario))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("COUPON_EXHAUSTED"));

        // Liberar sin uso activo, o si otro proceso ya lo liberó, no devuelve nada.
        when(redemptions.findByBookingIdAndEstado(reserva, "ACTIVO")).thenReturn(Optional.empty());
        service.liberar(reserva);
        verify(coupons, never()).restarUso(any(), any());
    }

    // ------------------------------------------------------------------ reserva y tramo

    private static Booking reserva(String... asientos) {
        return new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, List.of(asientos),
                new BigDecimal("100000"), "COP", AHORA, null, UUID.randomUUID(), AHORA);
    }

    @Test
    void laTarifaDeLaReservaNuncaQuedaNegativa() {
        Booking b = reserva("1", "2");
        b.asignarTarifa(new BigDecimal("185000"), new BigDecimal("500000"), UUID.randomUUID(), "TODO");
        assertThat(b.getTotal()).isEqualByComparingTo("0");
        assertThat(b.getDescuentoCupon()).isEqualByComparingTo("185000");
        assertThat(b.getPrecioUnitario()).isEqualByComparingTo("92500");
        b.asignarTarifa(new BigDecimal("185000"), null, null, null);
        assertThat(b.getTotal()).isEqualByComparingTo("185000");
        assertThat(b.getTramo()).isNull();
        b.asignarTramo(null);
        assertThat(b.getTramo()).isNull();
        b.asignarTramo(new Booking.Tramo(2, "Fusagasugá", 4, "Ibagué"));
        assertThat(b.getTramo().paradaBajada()).isEqualTo("Ibagué");
    }

    @Test
    void reprogramarUnTramoExigeLasMismasParadas() {
        Booking b = reserva("1");
        b.asignarViaje(new Booking.Viaje("Bogotá", "Ibagué", AHORA, "E"));
        CatalogClient.ItinerarySummary mismo = new CatalogClient.ItinerarySummary(UUID.randomUUID(), " bogota ",
                "IBAGUÉ", AHORA, null, "E", "P");
        RescheduleService.validarMismoTramo(b, null, mismo);
        Booking.Tramo otroTramo = new Booking.Tramo(2, "Fusagasugá", 4, "Ibagué");
        assertThatThrownBy(() -> RescheduleService.validarMismoTramo(b, otroTramo, mismo))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SEGMENT_MISMATCH"));

        b.asignarTramo(new Booking.Tramo(2, "Fusagasugá", 4, "Ibagué"));
        RescheduleService.validarMismoTramo(b, new Booking.Tramo(1, "fusagasuga", 3, "Ibague"), mismo);
        Booking.Tramo otraBajada = new Booking.Tramo(2, "Fusagasugá", 3, "Girardot");
        assertThatThrownBy(() -> RescheduleService.validarMismoTramo(b, otraBajada, mismo))
                .isInstanceOf(ApiException.class);
        // Sin nombres conocidos no se puede comparar: se deja pasar.
        RescheduleService.validarMismoTramo(b, new Booking.Tramo(2, null, 4, null), mismo);
    }

    @Test
    void elManifiestoAgrupaPorParadaDeAbordajeEnElOrdenDeLaRuta() {
        UUID b = UUID.randomUUID();
        List<ManifestRow> filas = List.of(
                new ManifestRow("C", "CC", "3", "9", b, "EMITIDO", null, 3, "Girardot", "Ibagué"),
                new ManifestRow("A", "CC", "1", "10", b, "EMITIDO", null),
                new ManifestRow("B", "CC", "2", "2", b, "USADO", AHORA),
                new ManifestRow("D", "CC", "4", "5", b, "EMITIDO", null, 2, "Fusagasugá", null));

        List<ManifestService.Parada> grupos = ManifestService.porParada(filas, "Bogotá", "Ibagué");

        assertThat(grupos).extracting(ManifestService.Parada::parada).containsExactly("Bogotá", "Fusagasugá", "Girardot");
        assertThat(grupos.get(0).pasajeros()).extracting(ManifestRow::nombre).containsExactly("B", "A");
        assertThat(grupos.get(0).pasajeros().get(0).paradaBajada()).isEqualTo("Ibagué");
        assertThat(grupos.get(1).pasajeros().get(0).paradaBajada()).isEqualTo("Ibagué");
        assertThat(grupos.get(2).orden()).isEqualTo(3);
    }

    @Test
    void elCorreoDelTiqueteMuestraElTramo() {
        Booking b = reserva("8");
        b.asignarTramo(new Booking.Tramo(2, "Fusagasugá", 4, null));

        String html = TicketMailService.html(null, b, List.of("Ana"));

        assertThat(html).contains("Sube en").contains("Fusagasugá").contains("Baja en");
        assertThat(TicketMailService.html(null, reserva("9"), List.of("Ana"))).doesNotContain("Sube en");
        Booking soloBajada = reserva("10");
        soloBajada.asignarTramo(new Booking.Tramo(null, null, 4, "Ibagué"));
        assertThat(TicketMailService.html(null, soloBajada, List.of("Ana"))).contains("Baja en").contains("Ibagué");
    }

    @Test
    void elTramoSeConoceConCualquieraDeSusDatos() {
        for (Booking.Tramo t : List.of(new Booking.Tramo(2, null, null, null), new Booking.Tramo(null, null, 3, null),
                new Booking.Tramo(null, "A", null, null), new Booking.Tramo(null, null, null, "B"))) {
            Booking b = reserva("1");
            b.asignarTramo(t);
            assertThat(b.getTramo()).isEqualTo(t);
        }
    }

    @Test
    void elPdfDelTiqueteConSoloUnaParadaDelTramo() {
        for (String[] paradas : new String[][]{{null, "Ibagué"}, {"Fusagasugá", null}, {null, null}}) {
            byte[] pdf = TicketPdf.render(new TicketPdf.Datos(UUID.randomUUID(), UUID.randomUUID(), "EMITIDO", "A",
                    "B", AHORA, "E", "P", List.of("1"), List.of(), BigDecimal.TEN, "COP", "qr-contenido",
                    paradas[0], paradas[1]));
            assertThat(pdf).startsWith("%PDF".getBytes());
        }
    }

    // ------------------------------------------------------------------ comprobantes

    @Test
    void metodoYViajeDelComprobante() {
        Payment tarjeta = new Payment(UUID.randomUUID(), BigDecimal.TEN, "k", "TARJETA", "4242", "VISA", null, AHORA);
        Payment pse = new Payment(UUID.randomUUID(), BigDecimal.TEN, "k2", "PSE", null, null, "Bancolombia", AHORA);
        Payment efectivo = Payment.efectivo(UUID.randomUUID(), BigDecimal.TEN, "k3", AHORA);
        assertThat(ReceiptService.metodo(tarjeta)).isEqualTo("TARJETA VISA **** 4242");
        assertThat(ReceiptService.metodo(pse)).isEqualTo("PSE Bancolombia");
        assertThat(ReceiptService.metodo(efectivo)).isEqualTo("EFECTIVO");

        Booking sinViaje = reserva("1");
        assertThat(ReceiptService.viaje(sinViaje)).isNull();
        sinViaje.asignarTramo(new Booking.Tramo(2, "Fusagasugá", 4, "Ibagué"));
        assertThat(ReceiptService.viaje(sinViaje)).isEqualTo("sube en Fusagasugá, baja en Ibagué");
        Booking conViaje = reserva("1");
        conViaje.asignarViaje(new Booking.Viaje("Bogotá", "Ibagué", AHORA, "E"));
        assertThat(ReceiptService.viaje(conViaje)).isEqualTo("Bogotá → Ibagué");
        conViaje.asignarTramo(new Booking.Tramo(2, "Fusagasugá", 4, "Ibagué"));
        assertThat(ReceiptService.viaje(conViaje)).isEqualTo("Bogotá → Ibagué (sube en Fusagasugá, baja en Ibagué)");
        conViaje.asignarTramo(new Booking.Tramo(2, "Fusagasugá", 4, null));
        assertThat(ReceiptService.viaje(conViaje)).isEqualTo("Bogotá → Ibagué");
    }

    @Test
    void elPdfDelComprobanteSinNitNiCupon() {
        byte[] pdf = ReceiptPdf.render(new ReceiptPdf.Datos("Comprobante de pago", "TB-00000001", AHORA, "Expreso",
                null, UUID.randomUUID(), null, "EFECTIVO", List.of(new ReceiptPdf.Linea("Ana", "ADULTO", "1", null,
                null)), BigDecimal.TEN, null, null, "Total pagado", BigDecimal.TEN, "COP", null));
        assertThat(pdf).startsWith("%PDF".getBytes());
        assertThat(ReceiptPdf.dinero(null, "COP")).isEqualTo("-");
        assertThat(ReceiptPdf.dinero(new BigDecimal("148000.00"), "COP")).isEqualTo("148000 COP");
    }
}
