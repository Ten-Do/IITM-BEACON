package com.iitm.beacon.config;

/**
 * Delivers a moderation-outcome notification email to a visitor. Feature
 * slices depend only on this abstraction, never on {@link
 * org.springframework.mail.javamail.JavaMailSender} directly. A message that
 * can't be delivered surfaces as a {@link
 * org.springframework.mail.MailException}, whose message may name the
 * recipient — log its type only.
 */
public interface NotificationMailer {

    void send(String toEmail, String subject, String body);
}
