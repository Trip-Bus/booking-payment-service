package com.tripbus.booking.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/** El claim "role" de identity se convierte en la autoridad ROLE_xxx (US-005). */
class JwtRoleConverterTest {

    private final JwtRoleConverter converter = new JwtRoleConverter();

    @Test
    void elClaimRoleSeConvierteEnAutoridadYSinElNoHayNinguna() {
        Jwt conRol = Jwt.withTokenValue("t").header("alg", "none").subject("u1").claim("role", "OPERADOR").build();
        Jwt sinRol = Jwt.withTokenValue("t").header("alg", "none").subject("u2").claim("x", "y").build();

        AbstractAuthenticationToken operador = converter.convert(conRol);
        AbstractAuthenticationToken anonimo = converter.convert(sinRol);

        assertThat(operador.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_OPERADOR");
        assertThat(operador.getName()).isEqualTo("u1");
        assertThat(anonimo.getAuthorities()).isEmpty();
        assertThat(CurrentUser.email(operador)).isNull();
        assertThat(CurrentUser.role(null)).isNull();
    }
}
