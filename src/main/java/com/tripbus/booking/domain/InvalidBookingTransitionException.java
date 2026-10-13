package com.tripbus.booking.domain;

/** Cambio de estado de la reserva que la saga no permite (ver {@link Booking#cambiarEstado}). */
public class InvalidBookingTransitionException extends IllegalStateException {

    private final BookingStatus desde;
    private final BookingStatus hacia;

    public InvalidBookingTransitionException(BookingStatus desde, BookingStatus hacia) {
        super("Transición de reserva no permitida: " + desde + " -> " + hacia);
        this.desde = desde;
        this.hacia = hacia;
    }

    public BookingStatus getDesde() {
        return desde;
    }

    public BookingStatus getHacia() {
        return hacia;
    }
}
