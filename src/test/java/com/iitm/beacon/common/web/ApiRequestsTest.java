package com.iitm.beacon.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link ApiRequests}: a request is the JSON API's when its path within the
 * application is {@code /api} or lies under {@code /api/}; everything else
 * is one of the site's pages. Decides whether an error is answered as JSON
 * or as the HTML error page.
 */
class ApiRequestsTest {

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api",
        "/api/",
        "/api/gallery/testimonials",
        "/api/moderation/testimonials/abc/approve",
        "/api/nothing-here",
        "//api/gallery/testimonials",
        "/api;jsessionid=abc/gallery/testimonials",
        "/%61pi/gallery/testimonials"
    })
    void apiPaths_areApiRequests(String uri) {
        assertThat(ApiRequests.matches(request(uri))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "/",
        "/gallery",
        "/gallery/api",
        "/apix",
        "/api-docs",
        "/API/gallery/testimonials",
        "/error",
        "/css/beacon.css",
        "/moderation/queue"
    })
    void everythingElse_isAPage(String uri) {
        assertThat(ApiRequests.matches(request(uri))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/beacon/api/gallery/testimonials", "/beacon/api"})
    void theContextPath_isNotPartOfThePath(String uri) {
        MockHttpServletRequest request = request(uri);
        request.setContextPath("/beacon");

        assertThat(ApiRequests.matches(request)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/gallery/testimonials", "/api"})
    void underAContextPathCalledApi_onlyThePathAfterItCounts(String uri) {
        MockHttpServletRequest request = request(uri);
        request.setContextPath("/api");

        assertThat(ApiRequests.matches(request)).isFalse();
    }
}
