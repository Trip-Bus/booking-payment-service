package com.tripbus.booking.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * passengerId: el que usa PATCH /tickets/{id}/passengers/{passengerId} (US-152). tipoPasajero, categoriaAsiento y precio:
 * desglose del precio de catalog (US-138/US-141); null en reservas anteriores al Sprint 6.
 */
public record PassengerResponse(UUID passengerId, String nombre, String tipoDocumento, String numeroDocumento,
                                String asiento, String tipoPasajero, String categoriaAsiento, BigDecimal precio) {

    public PassengerResponse(UUID passengerId, String nombre, String tipoDocumento, String numeroDocumento,
                             String asiento) {
        this(passengerId, nombre, tipoDocumento, numeroDocumento, asiento, null, null, null);
    }
}
