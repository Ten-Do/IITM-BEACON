package com.iitm.beacon.config;

/**
 * Delivers an OTP code to an email address. Both {@code adminauth.OtpService}
 * and {@code submission.VisitorOtpService} depend only on this abstraction,
 * never on {@link org.springframework.mail.javamail.JavaMailSender} directly.
 */
public interface OtpMailer {

    void sendOtp(String toEmail, String code);
}
