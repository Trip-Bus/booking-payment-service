package com.tripbus.booking.common.events;

import java.util.Set;

/** Manejador de un conjunto de tipos de evento. Se ejecuta dentro de una transacción. */
public interface DomainEventHandler {

    Set<String> eventTypes();

    void handle(EventEnvelope event);
}
