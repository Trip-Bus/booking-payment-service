package com.tripbus.booking.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Autenticación servicio-a-servicio para los endpoints /internal/**.
 * El API Gateway nunca enruta /internal/**, así que solo son alcanzables dentro de la red
 * interna y además exigen el header X-Internal-Token (secreto compartido).
 *
 * <p>No se registra como @Component (igual que en identity) para que Spring Boot no lo
 * agregue también como filtro global del contenedor: se instancia en SecurityConfig.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    public static final String INTERNAL_ROLE = "INTERNAL";

    private final byte[] expectedToken;

    public InternalTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && request.getRequestURI().startsWith("/internal/")
                && MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8))) {
            var auth = UsernamePasswordAuthenticationToken.authenticated(
                    "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_" + INTERNAL_ROLE)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
