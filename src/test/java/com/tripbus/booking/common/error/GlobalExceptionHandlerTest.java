package com.tripbus.booking.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Cada excepción se traduce al formato de error común {"error":{"code","message","details"}}. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static void esperar(ResponseEntity<ErrorResponse> r, HttpStatus status, String codigo) {
        assertThat(r.getStatusCode()).isEqualTo(status);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().error().code()).isEqualTo(codigo);
    }

    @Test
    void apiExceptionConservaStatusCodigoYDetalles() {
        ResponseEntity<ErrorResponse> r = handler.handleApi(new ApiException(HttpStatus.CONFLICT, "X", "m",
                Map.of("k", "v")));

        esperar(r, HttpStatus.CONFLICT, "X");
        assertThat(r.getBody().error().details()).containsEntry("k", "v");
        assertThat(ApiException.badRequest("B", "b").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ApiException.unavailable("U", "u").getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorResponse.of("C", "c").error().details()).isEmpty();
    }

    @Test
    void validacionDeCamposYParametros() throws Exception {
        BeanPropertyBindingResult errores = new BeanPropertyBindingResult(new Object(), "body");
        errores.addError(new org.springframework.validation.FieldError("body", "holdId", "holdId es obligatorio"));
        MethodParameter parametro = new MethodParameter(Object.class.getMethod("toString"), -1);

        ResponseEntity<ErrorResponse> campos = handler.handleValidation(new MethodArgumentNotValidException(parametro,
                errores));
        esperar(campos, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(campos.getBody().error().details()).containsEntry("holdId", "holdId es obligatorio");

        esperar(handler.handleMethodValidation(mock(HandlerMethodValidationException.class)), HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR");
        esperar(handler.handleBadRequest(new MissingServletRequestParameterException("format", "String")),
                HttpStatus.BAD_REQUEST, "BAD_REQUEST");
    }

    @Test
    void metodoRecursoAccesoYAutenticacion() {
        esperar(handler.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("PUT")),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        esperar(handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "/nada")), HttpStatus.NOT_FOUND,
                "NOT_FOUND");
        esperar(handler.handleAccessDenied(new AccessDeniedException("no")), HttpStatus.FORBIDDEN, "FORBIDDEN");
        esperar(handler.handleAuthentication(new BadCredentialsException("no")), HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED");
    }

    @Test
    void unErrorInesperadoNoExponeDetalles() {
        ResponseEntity<ErrorResponse> r = handler.handleUnexpected(new IllegalStateException("secreto interno"));

        esperar(r, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(r.getBody().error().message()).doesNotContain("secreto");
    }
}
