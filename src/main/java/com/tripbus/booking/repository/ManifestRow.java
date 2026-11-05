package com.tripbus.booking.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * US-172: un pasajero del manifiesto. estadoTiquete EMITIDO = pendiente de abordar, USADO = abordó (usadoEn).
 * US-142: fromOrden, paradaAbordaje y paradaBajada del tramo comprado (null = el viaje completo).
 */
public record ManifestRow(String nombre, String tipoDocumento, String numeroDocumento, String asiento,
                          UUID bookingId, String estadoTiquete, Instant abordadoEn, Integer fromOrden,
                          String paradaAbordaje, String paradaBajada) {

    public ManifestRow(String nombre, String tipoDocumento, String numeroDocumento, String asiento, UUID bookingId,
                       String estadoTiquete, Instant abordadoEn) {
        this(nombre, tipoDocumento, numeroDocumento, asiento, bookingId, estadoTiquete, abordadoEn, null, null, null);
    }
}
