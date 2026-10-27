package com.tripbus.booking.events;

public final class BookingEvents {

    public static final String BOOKING_CREATED = "BookingCreated";
    public static final String PAYMENT_APPROVED = "PaymentApproved";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String TICKET_ISSUED = "TicketIssued";
    public static final String REFUND_PROCESSED = "RefundProcessed";
    /** US-150: el tiquete se cambió por el de una reserva nueva. */
    public static final String TICKET_RESCHEDULED = "TicketRescheduled";
    /** US-139 / US-152: cambio de asiento o corrección de datos del pasajero (campo "cambio"). */
    public static final String TICKET_UPDATED = "TicketUpdated";

    /** US-154: la empresa canceló o cambió el viaje de una reserva confirmada (con las opciones del pasajero). */
    public static final String BOOKING_AFFECTED = "BookingAffected";
    /** US-155: el sistema reubicó una reserva afectada en otro viaje. */
    public static final String BOOKING_RELOCATED = "BookingRelocated";

    public static final String HOLD_EXPIRED = "HoldExpired";
    /** Catalog: la empresa canceló el viaje. */
    public static final String ITINERARY_CANCELLED = "ItineraryCancelled";
    /** Catalog: la empresa reprogramó o retrasó el viaje (tipo REPROGRAMACION | RETRASO, minutosDiferencia). */
    public static final String ITINERARY_RESCHEDULED = "ItineraryRescheduled";

    private BookingEvents() {
    }
}
