package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.testsupport.TemplateEngines;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

/**
 * {@link PageAccessDeniedHandler}: a refused request to one of the site's
 * pages (a CSRF failure) is answered with the HTML error page and a 403 —
 * never JSON, never the refusal's own message.
 */
class PageAccessDeniedHandlerTest {

    private final ErrorPageRenderer renderer = new ErrorPageRenderer(TemplateEngines.classpathTemplates());
    private final PageAccessDeniedHandler handler = new PageAccessDeniedHandler(renderer);

    static Stream<AccessDeniedException> refusals() {
        return Stream.of(
                new MissingCsrfTokenException(null),
                new InvalidCsrfTokenException(
                        new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected-secret-token"), "sent-token"),
                new AccessDeniedException("internal reason at com.x.Y(Y.java:1)"));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void refusedPageRequest_isTheHtml403Page(AccessDeniedException refusal) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/moderation/queue/1/approve");
        request.setPreferredLocales(List.of(Locale.ENGLISH));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, refusal);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getContentAsString())
                .isEqualTo(renderer.html(HttpStatus.FORBIDDEN, Locale.ENGLISH))
                .doesNotContain("expected-secret-token", "sent-token", "internal reason", "Y.java", "{\"");
    }
}
