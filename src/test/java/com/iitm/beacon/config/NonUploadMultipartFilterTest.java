package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.ClientErrors.assertJsonError;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iitm.beacon.common.error.ErrorPageRenderer;
import com.iitm.beacon.common.error.ErrorResponseWriter;
import com.iitm.beacon.testsupport.TemplateEngines;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

/**
 * {@link NonUploadMultipartFilter}: only the three submission endpoints take
 * a large multipart body (photos); any other request with a multipart body
 * is refused before anything reads it — the CSRF check would otherwise have
 * the servlet container parse it, up to the upload limit of 1010 MB,
 * whoever sent it — unless its declared {@code Content-Length} is within the
 * small limit (16 KB here). Without a declared length it can't be bounded:
 * 411. Every other request passes untouched. {@code
 * NonUploadMultipartLimitTest} checks it over real HTTP.
 */
class NonUploadMultipartFilterTest {

    private static final int LIMIT = 16 * 1024;
    private static final String MULTIPART = "multipart/form-data; boundary=x";

    private final ErrorResponseWriter errors = new ErrorResponseWriter(Clock.systemUTC(),
            new ObjectMapper().registerModule(new JavaTimeModule()),
            new ErrorPageRenderer(TemplateEngines.classpathTemplates()));
    private final NonUploadMultipartFilter filter = new NonUploadMultipartFilter(DataSize.ofBytes(LIMIT), errors);

    /**
     * A request declaring {@code length} bytes of body ({@code -1}: no
     * length, as for a chunked body) that fails the test if anyone reads its
     * body or parameters: the filter must decide from the headers alone.
     */
    private static MockHttpServletRequest request(String method, String path, String contentType, long length) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path) {
            @Override
            public long getContentLengthLong() {
                return length;
            }

            @Override
            public int getContentLength() {
                return length > Integer.MAX_VALUE ? -1 : (int) length;
            }

            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("the body was read");
            }

            @Override
            public BufferedReader getReader() {
                throw new AssertionError("the body was read");
            }

            @Override
            public String getParameter(String name) {
                throw new AssertionError("the parameters were read");
            }

            @Override
            public Map<String, String[]> getParameterMap() {
                throw new AssertionError("the parameters were read");
            }

            @Override
            public Collection<Part> getParts() {
                throw new AssertionError("the parts were read");
            }
        };
        if (contentType != null) {
            request.setContentType(contentType);
        }
        if (length >= 0) {
            request.addHeader("Content-Length", length);
        }
        return request;
    }

    /** Runs the filter; the chain's request is null if the filter answered itself. */
    private static MockFilterChain run(NonUploadMultipartFilter filter, MockHttpServletRequest request,
            MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain;
    }

    private MockFilterChain run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        return run(filter, request, response);
    }

    // -- the submission endpoints: any size, the container's upload limits apply --

    @ParameterizedTest
    @CsvSource({"POST, /submissions/form", "POST, /api/submissions", "PUT, /api/submissions/mine"})
    void submissionEndpoint_passesAMultipartBodyOfAnySize(String method, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(request(method, path, MULTIPART, 1_059_061_760L), response);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource({"POST, /submissions/form", "POST, /api/submissions", "PUT, /api/submissions/mine"})
    void submissionEndpoint_passesAMultipartBodyOfUnknownLength(String method, String path) throws Exception {
        assertThat(run(request(method, path, MULTIPART, -1), new MockHttpServletResponse()).getRequest())
                .isNotNull();
    }

    // -- anything else: within the small limit only --

    @ParameterizedTest
    @CsvSource({
        "POST, /admin/login/request",
        "POST, /gallery/1/contact",
        "POST, /api/catalog/topics",
        "POST, /no/such/path",
        // The submission paths with another method, or a path next to them.
        "GET, /submissions/form",
        "PUT, /submissions/form",
        "POST, /submissions/form/",
        "POST, /submissions/formx",
        "POST, /SUBMISSIONS/FORM",
        "PUT, /api/submissions",
        "POST, /api/submissions/mine",
        "PATCH, /api/submissions/mine",
        "DELETE, /api/submissions/mine",
        "POST, /api/submissions/otp/request",
        "GET, /moderation/queue"
    })
    void otherRequest_withAMultipartBodyOverTheLimit_isRefused413_withoutReachingTheChain(String method, String path)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(request(method, path, MULTIPART, LIMIT + 1), response);

        assertThat(chain.getRequest()).as("passed on").isNull();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void otherRequest_atExactlyTheLimit_passes() throws Exception {
        assertThat(run(request("POST", "/admin/login/request", MULTIPART, LIMIT), new MockHttpServletResponse())
                        .getRequest())
                .isNotNull();
    }

    @Test
    void otherRequest_withASmallMultipartBody_passes() throws Exception {
        assertThat(run(request("POST", "/admin/login/request", MULTIPART, 300), new MockHttpServletResponse())
                        .getRequest())
                .isNotNull();
    }

    @Test
    void otherRequest_withAMultipartBodyOfUnknownLength_isRefused411() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(request("POST", "/admin/login/request", MULTIPART, -1), response);

        assertThat(chain.getRequest()).isNull();
        assertHtmlErrorPage(response, 411);
    }

    /** Whatever its spelling: the container and Spring treat any {@code multipart/*} type as multipart. */
    @ParameterizedTest
    @ValueSource(strings = {
        "multipart/form-data", "Multipart/Form-Data; boundary=x", "MULTIPART/FORM-DATA", "multipart/mixed; boundary=x",
        " multipart/form-data; boundary=x"
    })
    void anyMultipartContentType_isLimited(String contentType) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request("POST", "/admin/login/request", contentType, LIMIT + 1), response);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    /** Other bodies aren't parsed by the CSRF check (form posts have their own 2 MB container limit). */
    @ParameterizedTest
    @ValueSource(strings = {"application/x-www-form-urlencoded", "application/json", "text/plain", "multipartx/y"})
    void nonMultipartBody_passesWhateverItsSize(String contentType) throws Exception {
        assertThat(run(request("POST", "/admin/login/request", contentType, 1_000_000_000L),
                        new MockHttpServletResponse())
                .getRequest())
                .isNotNull();
    }

    @Test
    void noContentType_passes() throws Exception {
        assertThat(run(request("POST", "/admin/login/request", null, 1_000_000_000L), new MockHttpServletResponse())
                        .getRequest())
                .isNotNull();
    }

    // -- how the refusal is answered: by path, like every error (decision 33) --

    @Test
    void refusedApiRequest_getsTheJsonErrorResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request("POST", "/api/catalog/topics", MULTIPART, LIMIT + 1), response);

        assertJsonError(response, 413, "The request body is too large.", "/api/catalog/topics");
    }

    @Test
    void refusedApiRequestOfUnknownLength_getsTheJsonErrorResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request("POST", "/api/catalog/topics", MULTIPART, -1), response);

        assertJsonError(response, 411, "The request must state its Content-Length.", "/api/catalog/topics");
    }

    @Test
    void refusedPageRequest_getsTheHtmlErrorPage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request("POST", "/admin/login/request", MULTIPART, LIMIT + 1), response);

        assertHtmlErrorPage(response, 413);
    }

    @Test
    void limitOfZero_refusesEveryMultipartBodyOutsideTheSubmissionEndpoints() throws Exception {
        NonUploadMultipartFilter none = new NonUploadMultipartFilter(DataSize.ofBytes(0), errors);
        MockHttpServletResponse refused = new MockHttpServletResponse();

        run(none, request("POST", "/admin/login/request", MULTIPART, 1), refused);

        assertThat(refused.getStatus()).isEqualTo(413);
        assertThat(run(none, request("POST", "/api/submissions", MULTIPART, 1), new MockHttpServletResponse())
                        .getRequest())
                .isNotNull();
    }
}
