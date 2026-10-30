package com.tripbus.booking.config;

import com.tripbus.booking.common.security.InternalTokenFilter;
import com.tripbus.booking.common.security.JsonSecurityHandlers;
import com.tripbus.booking.common.security.JwtRoleConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JsonSecurityHandlers handlers,
            @Value("${tripbus.internal-token}") String internalToken) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/internal/**").hasRole(InternalTokenFilter.INTERNAL_ROLE)
                        // La pasarela llama el webhook sin JWT: se autentica con la firma HMAC.
                        .requestMatchers(HttpMethod.POST, "/webhooks/payment").permitAll()
                        // Banco de pruebas PSE: el navegador del pasajero llega redirigido, sin JWT. Solo se abren
                        // los métodos que usa la página y un único segmento (el id aleatorio del cobro).
                        .requestMatchers(HttpMethod.GET, "/payments/pse/banks", "/sandbox/pse/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/sandbox/pse/*").permitAll()
                        // Swagger (en prod springdoc está apagado y estas rutas dan 404) y página de error.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(new JwtRoleConverter()))
                        .authenticationEntryPoint(handlers.entryPoint()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .addFilterBefore(new InternalTokenFilter(internalToken), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
