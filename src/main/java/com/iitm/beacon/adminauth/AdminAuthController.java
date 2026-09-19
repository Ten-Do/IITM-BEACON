package com.iitm.beacon.adminauth;

import com.iitm.beacon.common.EmailNormalizer;
import com.iitm.beacon.common.error.OtpVerificationFailedException;
import com.iitm.beacon.common.error.TooManyRequestsException;
import com.iitm.beacon.common.ratelimit.RateLimiterService;
import com.iitm.beacon.common.security.SessionAuthenticator;
import com.iitm.beacon.config.AdminOtpProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin OTP request/verify + session establishment (UC-ADMIN-OTP-REQUEST,
 * UC-ADMIN-OTP-VERIFY, decision 4).
 */
@RestController
@RequestMapping("/api/admin/auth/otp")
public class AdminAuthController {

    private final OtpService otpService;
    private final RateLimiterService rateLimiterService;
    private final AdminOtpProperties otpProperties;
    private final SessionAuthenticator sessionAuthenticator;

    public AdminAuthController(
            OtpService otpService,
            RateLimiterService rateLimiterService,
            AdminOtpProperties otpProperties,
            SessionAuthenticator sessionAuthenticator) {
        this.otpService = otpService;
        this.rateLimiterService = rateLimiterService;
        this.otpProperties = otpProperties;
        this.sessionAuthenticator = sessionAuthenticator;
    }

    @PostMapping("/request")
    public ResponseEntity<Void> request(@Valid @RequestBody OtpRequestRequest body, HttpServletRequest req) {
        String email = EmailNormalizer.normalize(body.email());
        String ip = req.getRemoteAddr();
        boolean allowed = rateLimiterService.tryConsume(
                        "admin-otp-request:email",
                        email,
                        otpProperties.requestLimitPerEmail(),
                        otpProperties.requestWindowPerEmail())
                && rateLimiterService.tryConsume(
                        "admin-otp-request:ip",
                        ip,
                        otpProperties.requestLimitPerIp(),
                        otpProperties.requestWindowPerIp());
        if (!allowed) {
            throw new TooManyRequestsException("Too many OTP requests in a short window.");
        }
        otpService.requestOtp(email);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/verify")
    public ResponseEntity<Void> verify(
            @Valid @RequestBody OtpVerifyRequest body, HttpServletRequest req, HttpServletResponse res) {
        OtpVerifyResult result = otpService.verify(body.email(), body.code());
        if (result instanceof OtpVerifyResult.Rejected) {
            throw new OtpVerificationFailedException("Wrong or expired code.");
        }
        var verified = (OtpVerifyResult.Verified) result;
        sessionAuthenticator.login(req, res, verified.email(), "ADMIN");
        return ResponseEntity.ok().build();
    }
}
