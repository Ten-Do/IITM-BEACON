package com.iitm.beacon.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders the site's HTML error page ({@link ErrorPage}) — the answer to an
 * error on any page route, i.e. anything outside {@code /api/**}
 * (docs/architecture.md §15). The template is rendered straight from the
 * template engine, without a web request context, so that the same page is
 * available inside Spring MVC ({@link GlobalExceptionHandler}, {@link
 * ErrorPageViewResolver}) and outside it ({@link PageAccessDeniedHandler},
 * {@link PageNotFoundHandler} and {@link ErrorResponseWriter}, in the
 * security filter chain).
 */
@Component
public class ErrorPageRenderer {

    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final ITemplateEngine templateEngine;

    public ErrorPageRenderer(ITemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /** The page for {@code status}, as HTML. */
    public String html(HttpStatusCode status, Locale locale) {
        ErrorPage page = ErrorPage.forStatus(status.value());
        Context context = new Context(locale, Map.of("title", page.title(), "message", page.message()));
        return templateEngine.process(ErrorPage.TEMPLATE, context);
    }

    /** The page as an MVC response: {@code status}, the given headers (e.g. {@code Allow}), and the HTML. */
    public ResponseEntity<String> response(HttpStatusCode status, HttpHeaders headers, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(HTML)
                .body(html(status, request.getLocale()));
    }

    /** Writes the page straight to {@code response}, for a handler outside Spring MVC. */
    public void write(HttpStatusCode status, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String html = html(status, request.getLocale());
        response.setStatus(status.value());
        response.setContentType(HTML.toString());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(html);
    }
}
