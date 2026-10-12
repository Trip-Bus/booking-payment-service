package com.tripbus.booking.common.security;

import com.tripbus.booking.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

/** Utilidades para leer el usuario autenticado (claims del JWT emitido por identity). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            return UUID.fromString(token.getToken().getSubject());
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Se requiere un usuario autenticado");
    }

    public static String role(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            return token.getToken().getClaimAsString("role");
        }
        return null;
    }

    /** Claim "email" del JWT, o null si el token no lo trae (tokens emitidos antes de incluirlo). */
    public static String email(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            String email = token.getToken().getClaimAsString("email");
            return email != null && !email.isBlank() ? email.trim() : null;
        }
        return null;
    }

    public static UUID companyId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            String companyId = token.getToken().getClaimAsString("companyId");
            return companyId != null ? UUID.fromString(companyId) : null;
        }
        return null;
    }

    public static boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
}
