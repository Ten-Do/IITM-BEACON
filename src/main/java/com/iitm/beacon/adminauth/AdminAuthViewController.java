package com.iitm.beacon.adminauth;

import com.iitm.beacon.common.error.TooManyRequestsException;
import com.iitm.beacon.common.security.PostLoginRedirect;
import com.iitm.beacon.common.security.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttribute;

/**
 * Thymeleaf view layer for the admin login, in two steps exactly like the
 * visitor flow in {@code submission.SubmissionViewController}: an email page
 * ({@code adminauth/login}) that requests a code, then a separate code page
 * ({@code adminauth/login-code}) with a single OTP field. Delegates all
 * business logic to {@link OtpService} — the JSON API in {@link
 * AdminAuthController} already covers the same use cases for API consumers;
 * this controller only renders/drives the HTML form flow.
 *
 * <p>The email typed at the first step reaches the second in the HTTP
 * session ({@link #PENDING_EMAIL}), never in a URL — so it stays out of the
 * browser history and access logs. The code page shows it from there, and
 * "Resend code" ({@link #resendOtp}) and the verify use it from there too:
 * neither form carries it, and an email posted along is ignored. One browser
 * holds one pending admin email — the last one asked for — kept apart from
 * a pending visitor login's. Without one (a fresh browser, an expired
 * session), the code step goes back to the email step. Opening the email
 * step — "Use a different email" links there — forgets it, and so does a
 * successful login.
 *
 * <p>{@link OtpService} deliberately responds the same for any email, so a
 * request for a non-admin email also lands on the code page, and that page's
 * wording never confirms the email is the admin's.
 *
 * <p>{@link #requestOtp} catches {@link TooManyRequestsException} locally and
 * renders the code page with an error, rather than letting it reach {@code
 * GlobalExceptionHandler} (whose answer to a page is the site's generic
 * error page — a dead end for a form submission) — same convention as
 * {@code moderation.ModerationViewController} and {@code
 * submission.SubmissionViewController}. The code page rather than
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

    /** The session attribute carrying the email from the email step to the code step. */
    private static final String PENDING_EMAIL = "adminauth.pendingLoginEmail";

    private static final String LOGIN_EMAIL_VIEW = "adminauth/login";
    private static final String LOGIN_CODE_VIEW = "adminauth/login-code";
    private static final String REDIRECT_TO_LOGIN_EMAIL = "redirect:/admin/login";
    private static final String REDIRECT_TO_LOGIN_CODE = "redirect:/admin/login/code";
    /** The admin's pages a login may return to; anything else falls back to the queue. */
    private static final Set<String> ADMIN_PAGES = Set.of("/moderation/", "/catalog/");
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

    /** Also "Use a different email": the pending email, if any, is forgotten. */
    @GetMapping
    public String loginEmailForm(HttpServletRequest request) {
        forgetPendingEmail(request);
        return LOGIN_EMAIL_VIEW;
    }

    @PostMapping("/request")
    public String requestOtp(@RequestParam(required = false) String email, HttpServletRequest request, Model model) {
        if (email == null || email.isBlank()) {
            model.addAttribute("error", BLANK_EMAIL_MESSAGE);
            return LOGIN_EMAIL_VIEW;
        }
        request.getSession().setAttribute(PENDING_EMAIL, email);
        return sendCode(email, request, model);
    }

    /** "Resend code": a new code for the pending email; without one, back to the email step. */
    @PostMapping("/resend")
    public String resendOtp(
            @SessionAttribute(name = PENDING_EMAIL, required = false) String email,
            HttpServletRequest request,
            Model model) {
        if (email == null) {
            return REDIRECT_TO_LOGIN_EMAIL;
        }
        return sendCode(email, request, model);
    }

    // -- step 2: code --

    @GetMapping("/code")
    public String loginCodeForm(
            @SessionAttribute(name = PENDING_EMAIL, required = false) String email, Model model) {
        if (email == null) {
            return REDIRECT_TO_LOGIN_EMAIL;
        }
        model.addAttribute("email", email);
        return LOGIN_CODE_VIEW;
    }

    @PostMapping("/verify")
    public String verify(
            @SessionAttribute(name = PENDING_EMAIL, required = false) String email,
            @RequestParam(required = false) String code,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        if (email == null) {
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
        forgetPendingEmail(request);
        return "redirect:" + postLoginRedirect.resolve(request, response, ADMIN_PAGES, MODERATION_QUEUE);
    }

    // -- helpers --

    /** Asks for a code; rate-limited, the code page says so — a code sent earlier may still be valid. */
    private String sendCode(String email, HttpServletRequest request, Model model) {
        try {
            otpService.requestOtp(email, request.getRemoteAddr());
        } catch (TooManyRequestsException ex) {
            model.addAttribute("email", email);
            model.addAttribute("error", TOO_MANY_REQUESTS_MESSAGE);
            return LOGIN_CODE_VIEW;
        }
        return REDIRECT_TO_LOGIN_CODE;
    }

    /** Never creates a session just to forget something in it. */
    private static void forgetPendingEmail(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(PENDING_EMAIL);
        }
    }
}
