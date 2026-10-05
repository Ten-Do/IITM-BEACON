package com.iitm.beacon.submission;

import com.iitm.beacon.common.error.OtpVerificationFailedException;
import com.iitm.beacon.common.security.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Visitor login half of the {@code submission} slice: email OTP
 * request/verify/session (UC-VISITOR-LOGIN, decision 17), plus the session
 * ping. Create/edit form endpoints live in {@link SubmissionController}.
 */
@RestController
@RequestMapping("/api/submissions")
public class VisitorAuthController {

    private final VisitorOtpService visitorOtpService;
    private final SessionAuthenticator sessionAuthenticator;
    private final SubmissionService submissionService;

    public VisitorAuthController(
            VisitorOtpService visitorOtpService,
            SessionAuthenticator sessionAuthenticator,
            SubmissionService submissionService) {
        this.visitorOtpService = visitorOtpService;
        this.sessionAuthenticator = sessionAuthenticator;
        this.submissionService = submissionService;
    }

    @PostMapping("/otp/request")
    public ResponseEntity<Void> request(@Valid @RequestBody OtpRequestRequest body, HttpServletRequest req) {
        visitorOtpService.requestOtp(body.email(), req.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<VisitorOtpVerifyResponse> verify(
            @Valid @RequestBody OtpVerifyRequest body, HttpServletRequest req, HttpServletResponse res) {
        VisitorOtpVerifyResult result = visitorOtpService.verify(body.email(), body.code());
        if (result instanceof VisitorOtpVerifyResult.Rejected) {
            throw new OtpVerificationFailedException("Wrong or expired code.");
        }
        var verified = (VisitorOtpVerifyResult.Verified) result;
        sessionAuthenticator.login(req, res, verified.email(), "VISITOR");

        SubmissionModeResult modeResult = submissionService.determineMode(verified.email());
        VisitorOtpVerifyResponse response =
                new VisitorOtpVerifyResponse(modeResult.mode(), modeResult.testimonialId());
        return ResponseEntity.ok(response);
    }

    /**
     * Session ping for {@code static/js/session-check.js} on the visitor's
     * protected pages: 204 while the visitor session is alive (the request
     * itself keeps it alive), otherwise the security layer's JSON 401 (no or
     * expired session) or 403 (another role's session), which makes the
     * script reload the page and so go through the login redirect.
     */
    @GetMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void session() {
        // Reaching this method is the whole answer: SecurityConfig already required a visitor session.
    }
}
