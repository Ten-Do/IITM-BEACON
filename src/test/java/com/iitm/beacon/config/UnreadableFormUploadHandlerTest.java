package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.iitm.beacon.common.web.UploadFailure;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;
import org.springframework.web.servlet.support.SessionFlashMapManager;

/**
 * {@link UnreadableFormUploadHandler}: a CSRF failure on a submission-form
 * POST whose multipart body the servlet container could not read (a size or
 * part-count limit) — so its {@code _csrf} field could not be read either —
 * is answered like the form's own upload failure: back to the form with the
 * upload error. Any other CSRF failure goes to the next handler unchanged.
 */
class UnreadableFormUploadHandlerTest {

    private static final CsrfToken EXPECTED = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected-token");

    private final FlashMapManager flashMapManager = new SessionFlashMapManager();
    private final AccessDeniedHandler otherwise = mock(AccessDeniedHandler.class);
    private final UnreadableFormUploadHandler handler = new UnreadableFormUploadHandler(flashMapManager, otherwise);

    /** A multipart request whose {@code getParts()} fails as the container's does after a failed parse. */
    private static MockHttpServletRequest unreadableUpload(String method, String path, Exception parseFailure) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path) {
            @Override
            public Collection<Part> getParts() throws IOException, ServletException {
                if (parseFailure instanceof IOException io) {
                    throw io;
                }
                if (parseFailure instanceof ServletException servlet) {
                    throw servlet;
                }
                throw (RuntimeException) parseFailure;
            }
        };
        request.setContentType("multipart/form-data; boundary=x");
        return request;
    }

    private static MockHttpServletRequest readableUpload() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/submissions/form");
        request.setContentType("multipart/form-data; boundary=x");
        return request;
    }

    /** The flash attributes a GET of {@code path} in the same session would get (a {@link FlashMap}), if any. */
    private Map<String, Object> flashFor(MockHttpServletRequest redirected, String path) {
        MockHttpServletRequest next = new MockHttpServletRequest("GET", path);
        next.setSession(redirected.getSession());
        return flashMapManager.retrieveAndUpdate(next, new MockHttpServletResponse());
    }

    static Stream<Exception> parseFailures() {
        return Stream.of(
                new IllegalStateException("FileCountLimitExceededException: attachment"),
                new IllegalStateException("FileSizeLimitExceededException: exceeds its maximum permitted size"),
                new IOException("Stream ended unexpectedly"),
                new ServletException("not a multipart request"));
    }

    @ParameterizedTest
    @MethodSource("parseFailures")
    void formPostWhoseBodyCouldNotBeRead_goesBackToTheFormWithTheUploadError(Exception parseFailure)
            throws Exception {
        MockHttpServletRequest request = unreadableUpload("POST", "/submissions/form", parseFailure);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new MissingCsrfTokenException(null));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/submissions/form");
        assertThat(flashFor(request, "/submissions/form")).containsEntry("error", UploadFailure.MESSAGE);
        verifyNoInteractions(otherwise);
    }

    @Test
    void theUploadErrorIsForTheFormOnly() throws Exception {
        MockHttpServletRequest request =
                unreadableUpload("POST", "/submissions/form", new IllegalStateException("too large"));

        handler.handle(request, new MockHttpServletResponse(), new MissingCsrfTokenException(null));

        assertThat(flashFor(request, "/submissions/confirmation")).isNull();
    }

    @Test
    void formPostWhoseBodyWasRead_butTheTokenIsWrong_goesToTheNextHandler() throws Exception {
        MockHttpServletRequest request = readableUpload();
        MockHttpServletResponse response = new MockHttpServletResponse();
        InvalidCsrfTokenException denied = new InvalidCsrfTokenException(EXPECTED, "wrong");

        handler.handle(request, response, denied);

        verify(otherwise).handle(request, response, denied);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void formPostWithTheHeaderToken_goesToTheNextHandler_evenIfItsBodyCouldNotBeRead() throws Exception {
        // The header, not the body, was where the token was looked for: a wrong header is just a wrong token.
        MockHttpServletRequest request =
                unreadableUpload("POST", "/submissions/form", new IllegalStateException("too large"));
        request.addHeader("X-XSRF-TOKEN", "stale");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new InvalidCsrfTokenException(EXPECTED, "stale"));

        verify(otherwise).handle(any(), any(), any());
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void urlEncodedFormPost_goesToTheNextHandler() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/submissions/form");
        request.setContentType("application/x-www-form-urlencoded");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new MissingCsrfTokenException(null));

        verify(otherwise).handle(any(), any(), any());
        assertThat(response.getRedirectedUrl()).isNull();
    }

    static Stream<MockHttpServletRequest> otherUnreadableUploads() {
        return Stream.of(
                unreadableUpload("POST", "/api/submissions", new IllegalStateException("too large")),
                unreadableUpload("PUT", "/api/submissions/mine", new IllegalStateException("too large")),
                unreadableUpload("POST", "/submissions/form/extra", new IllegalStateException("too large")),
                unreadableUpload("POST", "/submissions/formatted", new IllegalStateException("too large")),
                unreadableUpload("POST", "/submissions/confirmation", new IllegalStateException("too large")),
                unreadableUpload("PUT", "/submissions/form", new IllegalStateException("too large")),
                unreadableUpload("POST", "/moderation/queue/1/approve", new IllegalStateException("too large")));
    }

    @ParameterizedTest
    @MethodSource("otherUnreadableUploads")
    void unreadableUploadAnywhereButTheFormPost_goesToTheNextHandler(MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new MissingCsrfTokenException(null));

        verify(otherwise).handle(any(), any(), any());
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(request.getSession(false)).isNull();
    }
}
