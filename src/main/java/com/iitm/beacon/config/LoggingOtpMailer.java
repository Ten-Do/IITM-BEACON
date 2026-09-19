package com.iitm.beacon.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test OTP delivery: logs the code instead of emailing it
 * (docs/architecture.md §13 — "dev: OTP logged (not emailed)"). Active for
 * every profile except {@code prod}, which also covers the test profile
 * since no profile is active during {@code mvn test}.
 */
@Component
@Profile("!prod")
public class LoggingOtpMailer implements OtpMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpMailer.class);

    @Override
    public void sendOtp(String toEmail, String code) {
        log.info("OTP for {}: {}", toEmail, code);
    }
}
