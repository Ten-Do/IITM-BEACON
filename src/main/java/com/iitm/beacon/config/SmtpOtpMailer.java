package com.iitm.beacon.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Production OTP delivery: sends the code by real email via SMTP
 * (docs/architecture.md §13 — "prod: real SMTP").
 */
@Component
@Profile("prod")
public class SmtpOtpMailer implements OtpMailer {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpOtpMailer(JavaMailSender mailSender, @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendOtp(String toEmail, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Your IITM Beacon login code");
        message.setText("Your IITM Beacon login code is: " + code);
        mailSender.send(message);
    }
}
