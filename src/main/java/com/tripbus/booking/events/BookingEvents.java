package com.tripbus.booking.events;

public final class BookingEvents {

    public static final String BOOKING_CREATED = "BookingCreated";
    public static final String PAYMENT_APPROVED = "PaymentApproved";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String TICKET_ISSUED = "TicketIssued";
    public static final String REFUND_PROCESSED = "RefundProcessed";

    public static final String HOLD_EXPIRED = "HoldExpired";

    private BookingEvents() {
    }
}
