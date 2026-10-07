package com.iitm.beacon.config;

import com.iitm.beacon.common.web.UploadFailure;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;

/**
 * Handles a CSRF failure on the submission form's POST whose multipart body
 * the servlet container could not read — a file over {@code max-file-size},
 * a body over {@code max-request-size}, more parts than {@code
 * max-part-count}, or a malformed body (BL-004).
 *
 * <p>The form carries its CSRF token in a hidden {@code _csrf} field, i.e.
 * inside that body. Looking for it makes the container parse the body in
 * the {@code CsrfFilter}, before the request reaches {@code
 * submission.SubmissionViewController}; when the parse fails, every field is
 * lost, the token with them, and the CSRF check fails. The request is
 * rejected all the same — it never reaches the controller — but the
 * visitor is sent back to the form with the same upload error the
 * controller shows for such an upload ({@link UploadFailure}), instead of the
 * 403 error page for a token they did send.
 *
 * <p>Every other CSRF failure goes to the next handler unchanged: one whose
 * body was read (the token was really missing or wrong), one that sent the
 * token in the {@code X-XSRF-TOKEN} header (its body was never the token's
 * source), and anything that isn't a multipart POST of the form.
 */
final class UnreadableFormUploadHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(UnreadableFormUploadHandler.class);

    private static final String FORM_PATH = "/submissions/form";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    /** The model attribute {@code submission/form.html} shows as its error banner. */
    private static final String ERROR_ATTRIBUTE = "error";

    private static final RequestMatcher FORM_POST =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, FORM_PATH);
    private static final StandardServletMultipartResolver MULTIPART = new StandardServletMultipartResolver();

    private final FlashMapManager flashMapManager;
    private final AccessDeniedHandler otherwise;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    UnreadableFormUploadHandler(FlashMapManager flashMapManager, AccessDeniedHandler otherwise) {
        this.flashMapManager = flashMapManager;
        this.otherwise = otherwise;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException, ServletException {
        if (!isUnreadableFormUpload(request)) {
            otherwise.handle(request, response, denied);
            return;
        }
        log.warn("Rejected multipart submission to {}: its body couldn't be read", request.getRequestURI());
        FlashMap flashMap = new FlashMap();
        flashMap.put(ERROR_ATTRIBUTE, UploadFailure.MESSAGE);
        flashMap.setTargetRequestPath(request.getContextPath() + FORM_PATH);
        flashMapManager.saveOutputFlashMap(flashMap, request, response);
        redirectStrategy.sendRedirect(request, response, FORM_PATH);
    }

    /**
     * The container keeps the failure of its parse and throws it from {@code
     * getParts()}, without parsing again; a body it did read just returns
     * its parts.
     */
    private static boolean isUnreadableFormUpload(HttpServletRequest request) {
        if (!FORM_POST.matches(request) || request.getHeader(CSRF_HEADER) != null || !MULTIPART.isMultipart(request)) {
            return false;
        }
        try {
            request.getParts();
            return false;
        } catch (IOException | ServletException | IllegalStateException ex) {
            return true;
        }
    }
}
