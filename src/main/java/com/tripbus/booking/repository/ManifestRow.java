package com.tripbus.booking.repository;

import java.time.Instant;
import java.util.UUID;

/** US-172: un pasajero del manifiesto. estadoTiquete EMITIDO = pendiente de abordar, USADO = abordó (usadoEn). */
public record ManifestRow(String nombre, String tipoDocumento, String numeroDocumento, String asiento,
                          UUID bookingId, String estadoTiquete, Instant abordadoEn) {
}
