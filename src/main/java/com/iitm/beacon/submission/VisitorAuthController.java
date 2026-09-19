package com.iitm.beacon.submission;

import com.iitm.beacon.common.EmailNormalizer;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.error.OtpVerificationFailedException;
import com.iitm.beacon.common.error.TooManyRequestsException;
import com.iitm.beacon.common.ratelimit.RateLimiterService;
import com.iitm.beacon.common.security.SessionAuthenticator;
import com.iitm.beacon.config.VisitorOtpProperties;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Visitor login half of the {@code submission} slice: email OTP
 * request/verify/session (UC-VISITOR-LOGIN, decision 17). Create/edit form
 * endpoints are M3.
 */
@RestController
@RequestMapping("/api/submissions/otp")
public class VisitorAuthController {

    private final VisitorOtpService visitorOtpService;
    private final RateLimiterService rateLimiterService;
    private final VisitorOtpProperties otpProperties;
    private final SessionAuthenticator sessionAuthenticator;
    private final EmailLookupHashService emailLookupHashService;
    private final TestimonialRepository testimonialRepository;

    public VisitorAuthController(
            VisitorOtpService visitorOtpService,
            RateLimiterService rateLimiterService,
            VisitorOtpProperties otpProperties,
            SessionAuthenticator sessionAuthenticator,
            EmailLookupHashService emailLookupHashService,
            TestimonialRepository testimonialRepository) {
        this.visitorOtpService = visitorOtpService;
        this.rateLimiterService = rateLimiterService;
        this.otpProperties = otpProperties;
        this.sessionAuthenticator = sessionAuthenticator;
        this.emailLookupHashService = emailLookupHashService;
        this.testimonialRepository = testimonialRepository;
    }

    @PostMapping("/request")
    public ResponseEntity<Void> request(@Valid @RequestBody OtpRequestRequest body, HttpServletRequest req) {
        String email = EmailNormalizer.normalize(body.email());
        boolean allowed = rateLimiterService.tryConsume(
                        "visitor-otp-request:email",
                        email,
                        otpProperties.requestLimitPerEmail(),
                        otpProperties.requestWindowPerEmail())
                && rateLimiterService.tryConsume(
                        "visitor-otp-request:ip",
                        req.getRemoteAddr(),
                        otpProperties.requestLimitPerIp(),
                        otpProperties.requestWindowPerIp());
        if (!allowed) {
            throw new TooManyRequestsException("Too many OTP requests in a short window.");
        }
        visitorOtpService.requestOtp(email);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/verify")
    public ResponseEntity<VisitorOtpVerifyResponse> verify(
            @Valid @RequestBody OtpVerifyRequest body, HttpServletRequest req, HttpServletResponse res) {
        VisitorOtpVerifyResult result = visitorOtpService.verify(body.email(), body.code());
        if (result instanceof VisitorOtpVerifyResult.Rejected) {
            throw new OtpVerificationFailedException("Wrong or expired code.");
        }
        var verified = (VisitorOtpVerifyResult.Verified) result;
        sessionAuthenticator.login(req, res, verified.email(), "VISITOR");

        String lookupHash = emailLookupHashService.hash(verified.email());
        VisitorOtpVerifyResponse response = testimonialRepository
                .findByEmailLookupHash(lookupHash)
                .map(t -> new VisitorOtpVerifyResponse(SubmissionMode.EDIT, t.getId()))
                .orElseGet(() -> new VisitorOtpVerifyResponse(SubmissionMode.CREATE, null));
        return ResponseEntity.ok(response);
    }
}
