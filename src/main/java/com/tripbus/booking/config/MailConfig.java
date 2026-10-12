package com.tripbus.booking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * US-033: JavaMailSender propio a partir de {@link MailProperties} (no spring.mail.*): así un MAIL_HOST
 * vacío no crea un cliente SMTP apuntando a la nada. Crear el bean no abre conexiones; si el envío está
 * deshabilitado, TicketMailService simplemente no lo usa.
 */
@Configuration
public class MailConfig {

    private static final String TIMEOUT_MS = "5000";

    @Bean
    public JavaMailSender ticketMailSender(MailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        props.put("mail.smtp.timeout", TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MS);
        if (StringUtils.hasText(properties.username())) {
            sender.setUsername(properties.username());
            sender.setPassword(properties.password());
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
        }
        return sender;
    }
}
