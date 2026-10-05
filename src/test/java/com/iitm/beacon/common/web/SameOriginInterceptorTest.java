package com.iitm.beacon.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * {@link SameOriginInterceptor} applies {@link SameOriginGuard} to exactly
 * the handlers marked {@link SameOriginOnly} (on the method or its class),
 * and answers a refused request itself: 403 with a short plain-text body.
 */
class SameOriginInterceptorTest {

    private final SameOriginInterceptor interceptor =
            new SameOriginInterceptor(new SameOriginGuard(new SameOriginProperties(List.of())));

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    static class Handlers {

        @SameOriginOnly
        public String guarded() {
            return "guarded";
        }

        public String open() {
            return "open";
        }
    }

    @SameOriginOnly
    static class GuardedHandlers {

        public String any() {
            return "any";
        }
    }

    private static HandlerMethod handler(Object bean, String method) throws NoSuchMethodException {
        return new HandlerMethod(bean, method);
    }

    private static MockHttpServletRequest requestWith(String... headers) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/gallery/1/contact");
        request.setServerName("localhost");
        request.setServerPort(80);
        for (int i = 0; i < headers.length; i += 2) {
            request.addHeader(headers[i], headers[i + 1]);
        }
        return request;
    }

    @Test
    void guardedHandler_sameOriginRequest_proceeds() throws Exception {
        boolean proceed = interceptor.preHandle(
                requestWith("Origin", "http://localhost"), response, handler(new Handlers(), "guarded"));

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void guardedHandler_requestWithoutOrigin_isAnswered403WithAShortPlainTextReason() throws Exception {
        boolean proceed = interceptor.preHandle(requestWith(), response, handler(new Handlers(), "guarded"));

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(response.getContentAsString())
                .isEqualTo(SameOriginInterceptor.REJECTION_MESSAGE)
                .hasSizeLessThan(120)
                .doesNotContain("Exception", "at com.");
    }

    @Test
    void guardedHandler_crossSiteFetch_isAnswered403() throws Exception {
        MockHttpServletRequest request = requestWith("Origin", "http://localhost", "Sec-Fetch-Site", "cross-site");

        assertThat(interceptor.preHandle(request, response, handler(new Handlers(), "guarded"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void handlerOfAGuardedClass_isGuardedToo() throws Exception {
        MockHttpServletRequest foreign = requestWith("Origin", "http://evil.example");

        assertThat(interceptor.preHandle(foreign, response, handler(new GuardedHandlers(), "any"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void unmarkedHandler_isNeverChecked() throws Exception {
        MockHttpServletRequest foreign = requestWith("Origin", "http://evil.example");

        assertThat(interceptor.preHandle(foreign, response, handler(new Handlers(), "open"))).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /** Static resources and other non-controller handlers are none of its business. */
    @Test
    void nonControllerHandler_isNeverChecked() throws Exception {
        assertThat(interceptor.preHandle(requestWith(), response, new ResourceHttpRequestHandler())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
