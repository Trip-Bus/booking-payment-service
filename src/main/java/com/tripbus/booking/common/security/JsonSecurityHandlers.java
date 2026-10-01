package com.tripbus.booking.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.booking.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Respuestas 401/403 en el mismo formato JSON que identity. */
@Component
public class JsonSecurityHandlers {

    private final ObjectMapper objectMapper;

    public JsonSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> write(response, HttpStatus.UNAUTHORIZED,
                ErrorResponse.of("UNAUTHENTICATED", "Se requiere un token de acceso válido para acceder a este recurso"));
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> write(response, HttpStatus.FORBIDDEN,
                ErrorResponse.of("FORBIDDEN", "No tiene permisos para acceder a este recurso"));
    }

    private void write(HttpServletResponse response, HttpStatus status, ErrorResponse body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
