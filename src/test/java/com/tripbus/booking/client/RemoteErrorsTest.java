package com.tripbus.booking.client;

import com.tripbus.booking.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Un 4xx remoto conserva su código de error; red y 5xx quedan como reintentables. */
class RemoteErrorsTest {

    private static HttpClientErrorException cliente(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void un4xxConCuerpoJsonConservaCodigoYMensaje() {
        RuntimeException e = RemoteErrors.translate("Seat Inventory", cliente(HttpStatus.CONFLICT,
                "{\"error\":{\"code\":\"SEAT_UNAVAILABLE\",\"message\":\"El asiento 14 no está disponible\"}}"));

        assertThat(e).isInstanceOfSatisfying(ApiException.class, api -> {
            assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(api.getCode()).isEqualTo("SEAT_UNAVAILABLE");
            assertThat(api.getMessage()).isEqualTo("El asiento 14 no está disponible");
        });
    }

    @Test
    void un4xxSinJsonUsaElCodigoGenerico() {
        RuntimeException e = RemoteErrors.translate("Catalog", cliente(HttpStatus.NOT_FOUND, "<html>no</html>"));

        assertThat(e).isInstanceOfSatisfying(ApiException.class, api -> {
            assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(api.getCode()).isEqualTo("REMOTE_ERROR");
            assertThat(api.getMessage()).isEqualTo("Catalog rechazó la operación");
        });
    }

    @Test
    void redY5xxSonReintentables() {
        RuntimeException red = RemoteErrors.translate("Catalog", new ResourceAccessException("timeout"));
        RuntimeException servidor = RemoteErrors.translate("Catalog",
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", HttpHeaders.EMPTY, new byte[0], null));

        assertThat(red).isInstanceOf(RemoteErrors.RemoteUnavailableException.class).hasMessageContaining("timeout");
        assertThat(servidor).isInstanceOf(RemoteErrors.RemoteUnavailableException.class)
                .hasMessageStartingWith("Catalog no está disponible");
    }
}
