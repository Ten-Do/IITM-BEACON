package com.iitm.beacon.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Production notification delivery: sends the given subject/body by real
 * email via SMTP (docs/architecture.md §13 — "prod: real SMTP").
 */
@Component
@Profile("prod")
public class SmtpNotificationMailer implements NotificationMailer {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpNotificationMailer(JavaMailSender mailSender, @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
