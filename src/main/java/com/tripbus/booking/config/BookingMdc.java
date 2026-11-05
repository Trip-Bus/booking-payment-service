package com.tripbus.booking.config;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * HT-10: pone el id de la reserva en el MDC ({@code bookingId}) mientras se procesa una parte de la saga fuera de
 * una petición /bookings/{id} (webhook de la pasarela, jobs). Al cerrarse deja el valor que había antes, así no le
 * quita el bookingId a la petición que la llamó.
 */
public final class BookingMdc implements AutoCloseable {

    private final String anterior;

    private BookingMdc(String anterior) {
        this.anterior = anterior;
    }

    public static BookingMdc con(UUID bookingId) {
        String anterior = MDC.get(RequestIdFilter.MDC_BOOKING);
        if (bookingId != null) {
            MDC.put(RequestIdFilter.MDC_BOOKING, bookingId.toString());
        }
        return new BookingMdc(anterior);
    }

    @Override
    public void close() {
        if (anterior == null) {
            MDC.remove(RequestIdFilter.MDC_BOOKING);
        } else {
            MDC.put(RequestIdFilter.MDC_BOOKING, anterior);
        }
    }
}
