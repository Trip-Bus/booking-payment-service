package com.tripbus.booking.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentación OpenAPI / Swagger UI (http://localhost:18004/swagger-ui.html). Solo en IDE y QA:
 * application-prod.yml apaga springdoc. Reservas, pagos y tiquetes exigen JWT: el botón "Authorize"
 * acepta el accessToken que emite identity en /auth/login. /webhooks/payment no usa JWT (firma HMAC).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI bookingOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        return new OpenAPI()
                .info(new Info()
                        .title("TripBus · Booking & Payment")
                        .description("Reservas, pago con tarjeta (sandbox), tiquetes con QR, idempotencia y saga de compra.")
                        .version(version))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
