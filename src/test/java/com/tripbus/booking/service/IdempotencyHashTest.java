package com.tripbus.booking.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyHashTest {

    @Test
    void elHashDependeDeTodasLasPartesYDeSuOrden() {
        assertThat(IdempotencyService.hash("a", "b")).isEqualTo(IdempotencyService.hash("a", "b"));
        assertThat(IdempotencyService.hash("a", "b")).isNotEqualTo(IdempotencyService.hash("b", "a"));
        assertThat(IdempotencyService.hash("ab", "")).isNotEqualTo(IdempotencyService.hash("a", "b"));
    }

    @Test
    void generaUnPngValidoParaElQr() {
        byte[] png = TicketService.qrPng("TRIPBUS1|prueba");
        assertThat(png).hasSizeGreaterThan(100);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
    }
}
