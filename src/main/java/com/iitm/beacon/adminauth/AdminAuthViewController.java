package com.iitm.beacon.adminauth;

import com.iitm.beacon.common.error.TooManyRequestsException;
import com.iitm.beacon.common.security.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Thymeleaf view layer for the admin login page: a single screen combining
 * email entry and OTP code entry (per {@code ui-design/AdminLogin.dc.html} —
 * unlike the visitor flow's two separate pages, the mockup shows both the
 * email field AND the code field on one form at once, not a staged reveal).
 * Delegates all business logic to {@link OtpService} — the JSON API in
 * {@link AdminAuthController} already covers the same use cases for API
 * consumers; this controller only renders/drives the HTML form flow.
 *
 * <p>{@link #requestOtp} catches {@link TooManyRequestsException} locally and
 * re-renders the page with an error, rather than letting it reach {@code
 * GlobalExceptionHandler} (a {@code @RestControllerAdvice}, which would write
 * a JSON body — the wrong response shape for a plain browser form
 * submission) — same convention as {@code moderation.ModerationViewController}
 * and {@code submission.SubmissionViewController}.
 */
@Controller
@RequestMapping("/admin/login")
public class AdminAuthViewController {

    private static final String LOGIN_VIEW = "adminauth/login";
    private static final String REDIRECT_TO_MODERATION_QUEUE = "redirect:/moderation/queue";
    private static final String WRONG_CODE_MESSAGE = "That code is wrong or has expired. Try again, or resend.";
    private static final String BLANK_EMAIL_MESSAGE = "Please enter the admin email address.";
    private static final String TOO_MANY_REQUESTS_MESSAGE =
            "Too many code requests in a short time. Please wait a moment and try again.";

    private final OtpService otpService;
    private final SessionAuthenticator sessionAuthenticator;

    public AdminAuthViewController(OtpService otpService, SessionAuthenticator sessionAuthenticator) {
        this.otpService = otpService;
        this.sessionAuthenticator = sessionAuthenticator;
    }

    @GetMapping
    public String loginForm(
            @RequestParam(required = false, defaultValue = "") String email,
            @RequestParam(required = false, defaultValue = "false") boolean sent,
            Model model) {
        model.addAttribute("email", email);
        model.addAttribute("sent", sent);
        return LOGIN_VIEW;
    }

    @PostMapping("/request")
    public String requestOtp(@RequestParam(required = false) String email, HttpServletRequest request, Model model) {
        if (email == null || email.isBlank()) {
            model.addAttribute("email", "");
            model.addAttribute("sent", false);
            model.addAttribute("error", BLANK_EMAIL_MESSAGE);
            return LOGIN_VIEW;
        }
        try {
            otpService.requestOtp(email, request.getRemoteAddr());
        } catch (TooManyRequestsException ex) {
            model.addAttribute("email", email);
            model.addAttribute("sent", false);
            model.addAttribute("error", TOO_MANY_REQUESTS_MESSAGE);
            return LOGIN_VIEW;
        }
        return "redirect:/admin/login?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&sent=true";
    }

    @PostMapping("/verify")
    public String verify(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String code,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        if (email == null || email.isBlank()) {
            model.addAttribute("email", "");
            model.addAttribute("sent", false);
            model.addAttribute("error", BLANK_EMAIL_MESSAGE);
            return LOGIN_VIEW;
        }
        OtpVerifyResult result = otpService.verify(email, code);
        if (result instanceof OtpVerifyResult.Rejected) {
            model.addAttribute("email", email);
            model.addAttribute("sent", false);
            model.addAttribute("error", WRONG_CODE_MESSAGE);
            return LOGIN_VIEW;
        }
        var verified = (OtpVerifyResult.Verified) result;
        sessionAuthenticator.login(request, response, verified.email(), "ADMIN");
        return REDIRECT_TO_MODERATION_QUEUE;
    }
}
