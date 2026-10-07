package com.iitm.beacon.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * Spring Security's documented request handler for the cookie-to-header CSRF
 * pattern ("Single-Page Applications" in its reference, 6.x), BL-004:
 *
 * <ul>
 *   <li>A token sent in the {@code X-XSRF-TOKEN} header is compared as is:
 *       a REST client or a page's script copies the {@code XSRF-TOKEN}
 *       cookie's value there.
 *   <li>Otherwise the token is read from the {@code _csrf} request
 *       parameter, where a server-rendered form puts it — masked with a
 *       fresh random pad on every render (BREACH protection), so it is
 *       unmasked first.
 *   <li>The token rendered into pages (the {@code _csrf} request attribute
 *       Thymeleaf's hidden form field reads) is always the masked one.
 * </ul>
 *
 * <p>Every request also loads the deferred token, so a response always
 * sets the cookie when the request didn't carry one yet — any GET hands a
 * REST client its token.
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        xor.handle(request, response, csrfToken);
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
