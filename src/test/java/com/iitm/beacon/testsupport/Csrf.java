package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * CSRF tokens for MockMvc requests, sent exactly the way this app's clients
 * send them (BL-004): the {@code XSRF-TOKEN} cookie's value echoed in the
 * {@code X-XSRF-TOKEN} header (a REST client), or the cookie plus the
 * masked token a server-rendered form carries in its hidden {@code _csrf}
 * field (a browser). Every state-changing request in a test adds one of
 * them explicitly; nothing adds a token by default.
 *
 * <p>Deliberately not spring-security-test's {@code csrf()}: it replaces the
 * {@code CsrfFilter}'s token repository with a session-based test one for
 * the rest of the cached application context — so the cookie behaviour
 * other tests check would depend on test order — and its {@code asHeader()}
 * sends the masked form token, which the cookie-to-header pattern rejects.
 */
public final class Csrf {

    public static final String COOKIE = "XSRF-TOKEN";
    public static final String HEADER = "X-XSRF-TOKEN";
    public static final String PARAMETER = "_csrf";

    private static final Pattern HIDDEN_FIELD =
            Pattern.compile("<input type=\"hidden\" name=\"_csrf\" value=\"([^\"]+)\"\\s*/?>");

    private Csrf() {
    }

    /** A REST client: a token in the cookie and the same value in the {@code X-XSRF-TOKEN} header. */
    public static RequestPostProcessor csrfHeader() {
        return request -> {
            String token = newToken();
            withCookie(request, token);
            request.addHeader(HEADER, token);
            return request;
        };
    }

    /** A browser posting a rendered form: a token in the cookie and its masked form in the {@code _csrf} field. */
    public static RequestPostProcessor csrfField() {
        return request -> {
            String token = newToken();
            withCookie(request, token);
            request.setParameter(PARAMETER, masked(token));
            return request;
        };
    }

    /** The masked (BREACH-safe) form of {@code token}, as a rendered form's hidden field carries it. */
    public static String masked(String token) {
        MockHttpServletRequest scratch = new MockHttpServletRequest();
        new XorCsrfTokenRequestAttributeHandler()
                .handle(scratch, new MockHttpServletResponse(), () -> new DefaultCsrfToken(HEADER, PARAMETER, token));
        return ((CsrfToken) scratch.getAttribute(CsrfToken.class.getName())).getToken();
    }

    /** The values of every hidden {@code _csrf} field in {@code html}, in document order. */
    public static List<String> hiddenFieldValues(String html) {
        Matcher m = HIDDEN_FIELD.matcher(html);
        List<String> values = new ArrayList<>();
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    /**
     * Asserts that every {@code <form method="post">} in {@code html} carries
     * a non-empty hidden {@code _csrf} field, and that there is at least
     * {@code expectedForms} such forms (so the check can't pass vacuously).
     */
    public static void assertEveryPostFormCarriesTheToken(String html, int expectedForms) {
        List<String> postForms = HtmlSnippets.elements(html, "form").stream()
                .filter(form -> HtmlSnippets.openingTag(form, "form").matches("(?s).*\\smethod=\"post\".*"))
                .toList();
        assertThat(postForms).as("POST forms on the page").hasSizeGreaterThanOrEqualTo(expectedForms);
        assertThat(postForms).allSatisfy(form -> assertThat(hiddenFieldValues(form))
                .as("hidden _csrf field of %s", HtmlSnippets.openingTag(form, "form"))
                .singleElement()
                .satisfies(value -> assertThat(value).isNotBlank()));
    }

    private static String newToken() {
        return UUID.randomUUID().toString();
    }

    private static void withCookie(MockHttpServletRequest request, String token) {
        List<Cookie> cookies = new ArrayList<>();
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (!COOKIE.equals(cookie.getName())) {
                    cookies.add(cookie);
                }
            }
        }
        cookies.add(new Cookie(COOKIE, token));
        request.setCookies(cookies.toArray(Cookie[]::new));
    }
}
