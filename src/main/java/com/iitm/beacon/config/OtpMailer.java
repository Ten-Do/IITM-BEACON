package com.iitm.beacon.config;

/**
 * Delivers an OTP code to an email address. Both {@code adminauth.OtpService}
 * and {@code submission.VisitorOtpService} depend only on this abstraction,
 * never on {@link org.springframework.mail.javamail.JavaMailSender} directly.
 * A code that can't be delivered surfaces as a {@link
 * org.springframework.mail.MailException}, whose message may name the
 * recipient; the services log only its type and answer as if it was sent.
 */
public interface OtpMailer {

    void sendOtp(String toEmail, String code);
}
