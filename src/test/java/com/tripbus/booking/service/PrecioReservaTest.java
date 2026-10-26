package com.tripbus.booking.service;

import com.tripbus.booking.client.CatalogClient;
import com.tripbus.booking.client.FlashOffersClient;
import com.tripbus.booking.client.SeatInventoryClient;
import com.tripbus.booking.common.error.ApiException;
import com.tripbus.booking.common.events.EventPublisher;
import com.tripbus.booking.repository.BookingRepository;
import com.tripbus.booking.repository.PassengerRepository;
import com.tripbus.booking.repository.PaymentRepository;
import com.tripbus.booking.repository.SagaLogRepository;
import com.tripbus.booking.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Precio por asiento de una retención (compra y US-150): oferta relámpago o catalog. */
class PrecioReservaTest {

    private final CatalogClient catalog = mock(CatalogClient.class);
    private final FlashOffersClient flashOffers = mock(FlashOffersClient.class);
    private final BookingService service = new BookingService(mock(BookingRepository.class),
            mock(PassengerRepository.class), mock(PaymentRepository.class), mock(TicketRepository.class),
            mock(SagaLogRepository.class), mock(SeatInventoryClient.class), catalog, flashOffers,
            mock(SagaLogger.class), mock(EventPublisher.class), mock(TransactionTemplate.class), Clock.systemUTC());
    private final UUID userId = UUID.randomUUID();

    private SeatInventoryClient.Hold hold(UUID offerId) {
        return new SeatInventoryClient.Hold(UUID.randomUUID(), UUID.randomUUID(), List.of("1"), userId, offerId, null,
                "ACTIVA", Instant.now(), 600);
    }

    @Test
    void conOfertaSeUsaElPrecioDeFlashOffersSiLaReservaEsDelUsuario() {
        SeatInventoryClient.Hold hold = hold(UUID.randomUUID());
        when(flashOffers.reservationByHold(hold.offerId(), hold.holdId())).thenReturn(new FlashOffersClient
                .OfferReservation(UUID.randomUUID(), hold.offerId(), hold.holdId(), userId, "1", new BigDecimal("50000"),
                "ACTIVA"));

        assertThat(service.precio(hold, userId)).isEqualTo(new BookingService.Precio(new BigDecimal("50000"), "COP"));
        assertThatThrownBy(() -> service.precio(hold, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OFFER_RESERVATION_MISMATCH"));
    }

    @Test
    void sinOfertaSeUsaCatalogYUnViajeNoProgramadoNoSeVende() {
        SeatInventoryClient.Hold hold = hold(null);
        when(catalog.price(hold.itineraryId())).thenReturn(
                new CatalogClient.Price(hold.itineraryId(), new BigDecimal("95000"), "COP", "PROGRAMADO"));
        assertThat(service.precio(hold, userId).unitario()).isEqualByComparingTo("95000");

        when(catalog.price(hold.itineraryId())).thenReturn(
                new CatalogClient.Price(hold.itineraryId(), new BigDecimal("95000"), "COP", "CANCELADO"));
        assertThatThrownBy(() -> service.precio(hold, userId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ITINERARY_NOT_AVAILABLE"));
    }
}
