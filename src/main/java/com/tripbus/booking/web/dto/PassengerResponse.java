package com.tripbus.booking.web.dto;

import java.util.UUID;

/** passengerId: el que usa PATCH /tickets/{id}/passengers/{passengerId} (US-152). */
public record PassengerResponse(UUID passengerId, String nombre, String tipoDocumento, String numeroDocumento,
                                String asiento) {
}
