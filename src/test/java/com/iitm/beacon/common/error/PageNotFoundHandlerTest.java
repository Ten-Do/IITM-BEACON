package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.testsupport.TemplateEngines;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/**
 * {@link PageNotFoundHandler}: a request to a path outside {@code /api/**}
 * that no route serves — refused by the security filter chain's {@code
 * denyAll()} tail — is answered with the site's HTML 404 page, whether the
 * request was anonymous (as an entry point) or came with a session (as an
 * access-denied handler): never JSON, never a redirect, never the refusal's
 * own message, and without creating a session.
 */
class PageNotFoundHandlerTest {

    private static final String INTERNALS = "internal reason at com.x.Y(Y.java:1)";

    private final ErrorPageRenderer renderer = new ErrorPageRenderer(TemplateEngines.classpathTemplates());
    private final PageNotFoundHandler handler = new PageNotFoundHandler(renderer);

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setPreferredLocales(List.of(Locale.ENGLISH));
        return request;
    }

    private void assertTheHtml404Page(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getContentAsString())
                .isEqualTo(renderer.html(HttpStatus.NOT_FOUND, Locale.ENGLISH))
                .contains("Page not found")
                .doesNotContain("internal reason", "Y.java", "{\"");
        assertThat(response.getHeader("Location")).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void anonymousRequest_isTheHtml404Page() throws Exception {
        MockHttpServletRequest request = request("GET", "/no-such-page");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(request, response, new InsufficientAuthenticationException(INTERNALS));

        assertTheHtml404Page(request, response);
    }

    @Test
    void requestWithASession_isTheHtml404Page() throws Exception {
        MockHttpServletRequest request = request("GET", "/favicon.ico");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException(INTERNALS));

        assertTheHtml404Page(request, response);
    }

    /** What the client asks for doesn't matter: the path alone chose the HTML page (decision 33). */
    @Test
    void requestAcceptingOnlyJson_isStillTheHtml404Page() throws Exception {
        MockHttpServletRequest request = request("POST", "/no-such-page");
        request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(request, response, new InsufficientAuthenticationException(INTERNALS));

        assertTheHtml404Page(request, response);
    }

    @Test
    void responseStartedAsJsonElsewhere_becomesTheHtmlPage() throws Exception {
        MockHttpServletRequest request = request("HEAD", "/gallery/1/extra");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        handler.handle(request, response, new AccessDeniedException(INTERNALS));

        assertTheHtml404Page(request, response);
    }
}
