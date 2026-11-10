package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.common.redis.LeaderLock;
import com.tripbus.booking.domain.Payment;
import com.tripbus.booking.domain.Refund;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-190: cálculo de la comisión, su reversión y el job que la completa (con mocks). */
class ComisionUnitTest {

    private static final Instant AHORA = Instant.parse("2026-11-02T15:00:00Z");

    @Test
    void laComisionSeRedondeaAlPesoMasCercano() {
        assertThat(Payment.comision(new BigDecimal("95555"), new BigDecimal("8"))).isEqualByComparingTo("7644");
        assertThat(Payment.comision(new BigDecimal("95555"), new BigDecimal("10"))).isEqualByComparingTo("9556");
        assertThat(Payment.comision(new BigDecimal("100000"), new BigDecimal("0"))).isEqualByComparingTo("0");
        assertThat(Payment.comision(new BigDecimal("33333.33"), new BigDecimal("12.5"))).isEqualByComparingTo("4167");
    }

    @Test
    void laFechaDeLaVentaEsLaDeColombia() {
        assertThat(CommissionService.fechaDeVenta(Instant.parse("2026-02-01T04:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(CommissionService.fechaDeVenta(Instant.parse("2026-02-01T05:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    void aprobarGuardaLaPrimeraFechaDeAprobacion() {
        Payment p = new Payment(UUID.randomUUID(), BigDecimal.TEN, "k", "4242", "VISA", AHORA);
        assertThat(p.getAprobadoEn()).isNull();
        p.aprobar(AHORA);
        p.aprobar(AHORA.plusSeconds(60));
        assertThat(p.getAprobadoEn()).isEqualTo(AHORA);
        assertThat(Payment.efectivo(UUID.randomUUID(), BigDecimal.ONE, "e", AHORA).getAprobadoEn()).isEqualTo(AHORA);
        assertThat(p.getComisionPorcentaje()).isNull();
        assertThat(p.getComisionValor()).isNull();
    }

    @Test
    void elReembolsoSinComisionRegistradaLaDejaParaElJob() {
        Payment p = new Payment(UUID.randomUUID(), new BigDecimal("50000"), "k", "4242", "VISA", AHORA);
        Refund refund = Refund.de(p, p.getMonto(), "AFECTADA_POR_LA_EMPRESA", AHORA);
        assertThat(refund.getComisionValor()).isNull();
        assertThat(refund.getPaymentId()).isEqualTo(p.getId());
        assertThat(refund.getMonto()).isEqualByComparingTo("50000");
    }

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    @Test
    void siProgramarLaComisionFallaNoSeFrenaNada() {
        CommissionService service = new CommissionService(payments, bookings, catalog, tx);
        UUID id = UUID.randomUUID();
        assertThatCode(() -> service.enSegundoPlano(t -> {
            throw new RejectedExecutionException("cola llena");
        }, id)).doesNotThrowAnyException();
        service.shutdown();
    }

    @Test
    void sinTransaccionActivaSeRegistraYaEnSegundoPlanoYNuncaLanza() {
        CommissionService service = new CommissionService(payments, bookings, catalog, tx);
        UUID id = UUID.randomUUID();
        when(payments.findById(id)).thenThrow(new IllegalStateException("BD caída"));

        service.registrarDespuesDelCommit(id);

        verify(payments, timeout(5000)).findById(id);
        assertThat(service.registrar(id)).isFalse();
        service.shutdown();
    }

    @Test
    void sinCondicionesDeCatalogNoSeGuardaNada() {
        CommissionService service = new CommissionService(payments, bookings, catalog, tx);
        Payment p = Payment.efectivo(UUID.randomUUID(), new BigDecimal("1000"), "e", AHORA);
        com.tripbus.booking.domain.Booking reserva = new com.tripbus.booking.domain.Booking(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, List.of("1"), new BigDecimal("1000"), "COP", AHORA, null,
                UUID.randomUUID(), AHORA);
        when(payments.findById(p.getId())).thenReturn(java.util.Optional.of(p));
        when(bookings.findById(p.getBookingId())).thenReturn(java.util.Optional.of(reserva));

        assertThat(service.registrar(p.getId())).isFalse();

        verify(catalog).companyTerms(eq(reserva.getCompanyId()), any());
        verify(payments, never()).registrarComision(any(), any(), any());
        service.shutdown();
    }

    @Test
    void elJobSoloCorreAlArrancarSiEstaHabilitadoYEnUnSoloNodo() {
        CommissionService service = mock(CommissionService.class);
        LeaderLock lock = mock(LeaderLock.class);

        new CommissionJob(payments, service, lock, false).onReady();
        verify(lock, never()).runIfLeader(any(), any(), any());

        CommissionJob job = new CommissionJob(payments, service, lock, true);
        job.onReady();
        job.scheduled();
        verify(lock, org.mockito.Mockito.times(2)).runIfLeader(eq("booking-commission"), eq(Duration.ofMinutes(14)),
                any());
    }

    @Test
    void elJobSaltaLasQueNoPuedeCompletarYSigueConLasDemas() {
        CommissionService service = mock(CommissionService.class);
        CommissionJob job = new CommissionJob(payments, service, mock(LeaderLock.class), true);
        UUID pendiente = UUID.randomUUID();
        UUID completa = UUID.randomUUID();
        // Primera página: una que no se puede (catalog caído) y una que sí; la segunda solo trae la pendiente.
        when(payments.findVentasSinComision(any(Pageable.class)))
                .thenReturn(List.of(pendiente, completa))
                .thenReturn(List.of(pendiente));
        when(service.registrar(eq(pendiente), anyMap())).thenReturn(false);
        when(service.registrar(eq(completa), anyMap())).thenReturn(true);

        assertThat(job.completar()).isEqualTo(1);

        verify(service).registrar(eq(completa), anyMap());
        verify(service).registrar(eq(pendiente), anyMap());
    }

    @Test
    void elJobSinVentasPendientesNoHaceNada() {
        CommissionService service = mock(CommissionService.class);
        when(payments.findVentasSinComision(any(Pageable.class))).thenReturn(List.of());

        assertThat(new CommissionJob(payments, service, mock(LeaderLock.class), true).completar()).isZero();
        verify(service, never()).registrar(any(UUID.class), anyMap());
    }
}
