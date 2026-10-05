package com.iitm.beacon.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link SameOriginOnly}: before a handler marked with it runs,
 * asks {@link SameOriginGuard} whether the request came from this site's own
 * pages, and if not answers it here — 403 with a one-line plain-text reason
 * (never the verdict's details, never a stack trace) — so the handler never
 * runs. Every other handler passes untouched. Registered for all paths by
 * {@code config.WebMvcConfig}.
 */
@Component
public class SameOriginInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SameOriginInterceptor.class);

    static final String REJECTION_MESSAGE = "Forbidden: this address only answers requests from this site's own pages.";

    private final SameOriginGuard guard;

    public SameOriginInterceptor(SameOriginGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method) || !requiresSameOrigin(method)) {
            return true;
        }
        SameOriginGuard.Verdict verdict = guard.check(request);
        if (verdict.allowed()) {
            return true;
        }
        // Only the handler and the verdict: the headers themselves are the sender's text.
        log.info("Refused a request to {}: {}", method.getShortLogMessage(), verdict);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.getWriter().write(REJECTION_MESSAGE);
        return false;
    }

    private static boolean requiresSameOrigin(HandlerMethod method) {
        return method.hasMethodAnnotation(SameOriginOnly.class)
                || method.getBeanType().isAnnotationPresent(SameOriginOnly.class);
    }
}
