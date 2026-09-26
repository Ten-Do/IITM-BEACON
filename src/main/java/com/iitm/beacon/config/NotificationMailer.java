package com.iitm.beacon.config;

/**
 * Delivers a moderation-outcome notification email to a visitor. Feature
 * slices depend only on this abstraction, never on {@link
 * org.springframework.mail.javamail.JavaMailSender} directly.
 */
public interface NotificationMailer {

    void send(String toEmail, String subject, String body);
}
