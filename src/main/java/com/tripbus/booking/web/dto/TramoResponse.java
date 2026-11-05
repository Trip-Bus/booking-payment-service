package com.tripbus.booking.web.dto;

/** US-142: tramo comprado (parada de abordaje y de bajada, con su orden en la ruta). */
public record TramoResponse(Integer fromOrden, String paradaAbordaje, Integer toOrden, String paradaBajada) {
}
