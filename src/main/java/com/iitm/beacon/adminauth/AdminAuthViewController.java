package com.iitm.beacon.adminauth;

import com.iitm.beacon.common.error.TooManyRequestsException;
import com.iitm.beacon.common.security.PostLoginRedirect;
import com.iitm.beacon.common.security.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Thymeleaf view layer for the admin login, in two steps exactly like the
 * visitor flow in {@code submission.SubmissionViewController}: an email page
 * ({@code adminauth/login}) that requests a code, then a separate code page
 * ({@code adminauth/login-code}) with a single OTP field. Delegates all
 * business logic to {@link OtpService} — the JSON API in {@link
 * AdminAuthController} already covers the same use cases for API consumers;
 * this controller only renders/drives the HTML form flow.
 *
 * <p>{@link OtpService} deliberately responds the same for any email, so a
 * request for a non-admin email also lands on the code page, and that page's
 * wording never confirms the email is the admin's.
 *
 * <p>{@link #requestOtp} catches {@link TooManyRequestsException} locally and
 * renders the code page with an error, rather than letting it reach {@code
 * GlobalExceptionHandler} (a {@code @RestControllerAdvice}, which would write
 * a JSON body — the wrong response shape for a plain browser form
 * submission) — same convention as {@code moderation.ModerationViewController}
 * and {@code submission.SubmissionViewController}. The code page rather than
 * the email page, whether the rejected request was the first one or a
 * "Resend code": an earlier code may already be on its way and still valid.
 *
 * <p>A successful {@link #verify} returns the admin to the moderation page
 * they were sent to the login from (an expired session), if any, via
 * {@link PostLoginRedirect}; otherwise to the moderation queue.
 */
@Controller
@RequestMapping("/admin/login")
public class AdminAuthViewController {

    private static final String LOGIN_EMAIL_VIEW = "adminauth/login";
    private static final String LOGIN_CODE_VIEW = "adminauth/login-code";
    private static final String REDIRECT_TO_LOGIN_EMAIL = "redirect:/admin/login";
    /** The admin's pages a login may return to; anything else falls back to the queue. */
    private static final Set<String> ADMIN_PAGES = Set.of("/moderation/");
    private static final String MODERATION_QUEUE = "/moderation/queue";
    private static final String WRONG_CODE_MESSAGE = "That code is wrong or has expired. Try again, or resend.";
    private static final String BLANK_EMAIL_MESSAGE = "Please enter the admin email address.";
    private static final String TOO_MANY_REQUESTS_MESSAGE = "Too many code requests in a short time. Please wait a"
            + " moment — if you already received a code, you can still enter it below.";

    private final OtpService otpService;
    private final SessionAuthenticator sessionAuthenticator;
    private final PostLoginRedirect postLoginRedirect;

    public AdminAuthViewController(
            OtpService otpService, SessionAuthenticator sessionAuthenticator, PostLoginRedirect postLoginRedirect) {
        this.otpService = otpService;
        this.sessionAuthenticator = sessionAuthenticator;
        this.postLoginRedirect = postLoginRedirect;
    }

    // -- step 1: email --

    @GetMapping
    public String loginEmailForm() {
        return LOGIN_EMAIL_VIEW;
    }

    @PostMapping("/request")
    public String requestOtp(@RequestParam(required = false) String email, HttpServletRequest request, Model model) {
        if (email == null || email.isBlank()) {
            model.addAttribute("error", BLANK_EMAIL_MESSAGE);
            return LOGIN_EMAIL_VIEW;
        }
        try {
            otpService.requestOtp(email, request.getRemoteAddr());
        } catch (TooManyRequestsException ex) {
            model.addAttribute("email", email);
            model.addAttribute("error", TOO_MANY_REQUESTS_MESSAGE);
            return LOGIN_CODE_VIEW;
        }
        return "redirect:/admin/login/code?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
    }

    // -- step 2: code --

    @GetMapping("/code")
    public String loginCodeForm(@RequestParam(required = false, defaultValue = "") String email, Model model) {
        if (email.isBlank()) {
            return REDIRECT_TO_LOGIN_EMAIL;
        }
        model.addAttribute("email", email);
        return LOGIN_CODE_VIEW;
    }

    @PostMapping("/verify")
    public String verify(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String code,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        if (email == null || email.isBlank()) {
            return REDIRECT_TO_LOGIN_EMAIL;
        }
        OtpVerifyResult result = otpService.verify(email, code);
        if (result instanceof OtpVerifyResult.Rejected) {
            model.addAttribute("email", email);
            model.addAttribute("error", WRONG_CODE_MESSAGE);
            return LOGIN_CODE_VIEW;
        }
        var verified = (OtpVerifyResult.Verified) result;
        sessionAuthenticator.login(request, response, verified.email(), "ADMIN");
        return "redirect:" + postLoginRedirect.resolve(request, response, ADMIN_PAGES, MODERATION_QUEUE);
    }
}
