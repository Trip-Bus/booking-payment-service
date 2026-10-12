package com.tripbus.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * US-033: SMTP para enviar el tiquete por correo (MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD,
 * MAIL_FROM). Sin MAIL_HOST el envío queda deshabilitado; no es obligatorio en ningún entorno.
 */
@ConfigurationProperties(prefix = "tripbus.mail")
public record MailProperties(String host, Integer port, String username, String password, String from) {

    public static final String FROM_POR_DEFECTO = "TripBus <no-reply@tripbus.co>";

    public MailProperties {
        port = port != null ? port : 1025;
        from = StringUtils.hasText(from) ? from : FROM_POR_DEFECTO;
    }

    public boolean enabled() {
        return StringUtils.hasText(host);
    }
}
