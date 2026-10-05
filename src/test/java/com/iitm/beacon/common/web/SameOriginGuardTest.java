package com.iitm.beacon.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.iitm.beacon.common.web.SameOriginGuard.Verdict;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The same-origin check behind {@link SameOriginOnly} endpoints: a request is
 * accepted only if its {@code Origin} header names this site, and its {@code
 * Sec-Fetch-Site} header, when the browser sends one, says {@code
 * same-origin}. "This site" is each request's own scheme, host and port,
 * unless {@code beacon.web.allowed-origins} lists the site's origins
 * explicitly (behind a reverse proxy).
 */
class SameOriginGuardTest {

    private static final SameOriginGuard REQUEST_DERIVED = new SameOriginGuard(new SameOriginProperties(List.of()));

    /** A request to {@code scheme://host:port/gallery/1/contact}, with the given headers (name, value, ...). */
    private static MockHttpServletRequest request(String scheme, String host, int port, String... headers) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/gallery/1/contact");
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        for (int i = 0; i < headers.length; i += 2) {
            request.addHeader(headers[i], headers[i + 1]);
        }
        return request;
    }

    private static MockHttpServletRequest localhost8080(String... headers) {
        return request("http", "localhost", 8080, headers);
    }

    // -- the Origin header, against the request's own origin --

    @Test
    void matchingOrigin_isAllowed() {
        assertThat(REQUEST_DERIVED.check(localhost8080("Origin", "http://localhost:8080")))
                .isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void noOriginHeader_isRejected() {
        assertThat(REQUEST_DERIVED.check(localhost8080())).isEqualTo(Verdict.NO_ORIGIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankOriginHeader_isRejected(String origin) {
        assertThat(REQUEST_DERIVED.check(localhost8080("Origin", origin))).isEqualTo(Verdict.NO_ORIGIN);
    }

    /** The opaque origin a browser sends from a sandboxed frame, a {@code file:} page or after some redirects. */
    @Test
    void nullOrigin_isRejected() {
        assertThat(REQUEST_DERIVED.check(localhost8080("Origin", "null"))).isEqualTo(Verdict.OTHER_ORIGIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://evil.example",
        "http://evil.example:8080",
        "http://localhost.evil.example:8080",
        "http://127.0.0.1:8080",
        "https://localhost:8080",
        "http://localhost:8081",
        "http://localhost",
    })
    void otherSchemeHostOrPort_isRejected(String origin) {
        assertThat(REQUEST_DERIVED.check(localhost8080("Origin", origin))).isEqualTo(Verdict.OTHER_ORIGIN);
    }

    /** Not how a browser serializes an origin: never taken for this site's, even when the host matches. */
    @ParameterizedTest
    @ValueSource(strings = {
        "localhost:8080",
        "//localhost:8080",
        "http://",
        "http://localhost:8080/",
        "http://localhost:8080/gallery",
        "http://localhost:8080?x=1",
        "http://localhost:8080#contact",
        "http://user@localhost:8080",
        "http://local host:8080",
        "http://localhost:8080, http://localhost:8080",
        "ftp://localhost:8080",
        "file://localhost:8080",
        "chrome-extension://localhost:8080",
        "http://localhost:99999",
    })
    void malformedOrNonWebOrigin_isRejected(String origin) {
        assertThat(REQUEST_DERIVED.check(localhost8080("Origin", origin))).isEqualTo(Verdict.OTHER_ORIGIN);
    }

    @Test
    void twoOriginHeaders_areRejected_evenIfBothMatch() {
        MockHttpServletRequest request =
                localhost8080("Origin", "http://localhost:8080", "Origin", "http://localhost:8080");

        assertThat(REQUEST_DERIVED.check(request)).isEqualTo(Verdict.OTHER_ORIGIN);
    }

    /** Scheme and host compare case-insensitively (a browser sends them lowercase, a Host header may not be). */
    @ParameterizedTest
    @CsvSource({
        "HTTP://LOCALHOST:8080, localhost",
        "http://LocalHost:8080, localhost",
        "http://localhost:8080, LOCALHOST",
    })
    void schemeAndHost_matchRegardlessOfCase(String origin, String serverName) {
        assertThat(REQUEST_DERIVED.check(request("http", serverName, 8080, "Origin", origin)))
                .isEqualTo(Verdict.ALLOWED);
    }

    /** A browser leaves the scheme's default port out of the Origin; the request still reports it. */
    @ParameterizedTest
    @CsvSource({
        "http, 80, http://localhost",
        "http, 80, http://localhost:80",
        "https, 443, https://localhost",
        "https, 443, https://localhost:443",
    })
    void defaultPort_matchesWithOrWithoutItBeingSpelledOut(String scheme, int port, String origin) {
        assertThat(REQUEST_DERIVED.check(request(scheme, "localhost", port, "Origin", origin)))
                .isEqualTo(Verdict.ALLOWED);
    }

    @ParameterizedTest
    @CsvSource({
        "http, 80, http://localhost:443",
        "https, 443, https://localhost:80",
        "https, 443, http://localhost",
        "http, 80, https://localhost",
    })
    void defaultPortOfTheOtherScheme_doesNotMatch(String scheme, int port, String origin) {
        assertThat(REQUEST_DERIVED.check(request(scheme, "localhost", port, "Origin", origin)))
                .isEqualTo(Verdict.OTHER_ORIGIN);
    }

    @Test
    void ipv6Host_matches() {
        assertThat(REQUEST_DERIVED.check(request("http", "[::1]", 8080, "Origin", "http://[::1]:8080")))
                .isEqualTo(Verdict.ALLOWED);
    }

    // -- Sec-Fetch-Site --

    @Test
    void secFetchSiteSameOrigin_isAllowed() {
        MockHttpServletRequest request =
                localhost8080("Origin", "http://localhost:8080", "Sec-Fetch-Site", "same-origin");

        assertThat(REQUEST_DERIVED.check(request)).isEqualTo(Verdict.ALLOWED);
    }

    /** Only browsers send it (and older ones don't): its absence alone is no reason to refuse. */
    @Test
    void secFetchSiteAbsent_isAllowed() {
        assertThat(REQUEST_DERIVED.check(localhost8080("Origin", "http://localhost:8080")))
                .isEqualTo(Verdict.ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-site", "cross-site", "none", "", "Same-Origin", "same-origin, cross-site"})
    void secFetchSiteOtherThanSameOrigin_isRejected_evenWithAMatchingOrigin(String secFetchSite) {
        MockHttpServletRequest request =
                localhost8080("Origin", "http://localhost:8080", "Sec-Fetch-Site", secFetchSite);

        assertThat(REQUEST_DERIVED.check(request)).isEqualTo(Verdict.NOT_SAME_ORIGIN_FETCH);
    }

    @Test
    void secFetchSiteSentTwice_isRejected_unlessBothSaySameOrigin() {
        MockHttpServletRequest mixed = localhost8080(
                "Origin", "http://localhost:8080", "Sec-Fetch-Site", "same-origin", "Sec-Fetch-Site", "cross-site");

        assertThat(REQUEST_DERIVED.check(mixed)).isEqualTo(Verdict.NOT_SAME_ORIGIN_FETCH);
    }

    @Test
    void foreignOrigin_isRejectedForItsOrigin_evenIfSecFetchSiteClaimsSameOrigin() {
        MockHttpServletRequest request =
                localhost8080("Origin", "http://evil.example", "Sec-Fetch-Site", "same-origin");

        assertThat(REQUEST_DERIVED.check(request)).isEqualTo(Verdict.OTHER_ORIGIN);
    }

    // -- beacon.web.allowed-origins --

    private static SameOriginGuard allowing(String... origins) {
        return new SameOriginGuard(new SameOriginProperties(Arrays.asList(origins)));
    }

    /** Behind a TLS-terminating proxy the app sees http://app:8080 while the browser is on https://beacon.example. */
    @Test
    void allowList_acceptsAListedOrigin_whateverTheRequestsOwnHost() {
        SameOriginGuard guard = allowing("https://beacon.example", "https://www.beacon.example");

        assertThat(guard.check(request("http", "app", 8080, "Origin", "https://www.beacon.example")))
                .isEqualTo(Verdict.ALLOWED);
        assertThat(guard.check(request("http", "app", 8080, "Origin", "https://beacon.example")))
                .isEqualTo(Verdict.ALLOWED);
    }

    /** The list replaces the request-derived origin: a spoofed Host header can't widen it. */
    @Test
    void allowList_rejectsTheRequestsOwnOrigin_whenItIsNotListed() {
        SameOriginGuard guard = allowing("https://beacon.example");

        assertThat(guard.check(request("http", "app", 8080, "Origin", "http://app:8080")))
                .isEqualTo(Verdict.OTHER_ORIGIN);
    }

    @Test
    void allowList_stillRequiresAnOriginHeader_andASameOriginFetch() {
        SameOriginGuard guard = allowing("https://beacon.example");

        assertThat(guard.check(request("http", "app", 8080))).isEqualTo(Verdict.NO_ORIGIN);
        assertThat(guard.check(request(
                        "http", "app", 8080, "Origin", "https://beacon.example", "Sec-Fetch-Site", "cross-site")))
                .isEqualTo(Verdict.NOT_SAME_ORIGIN_FETCH);
    }

    /** Entries are written by hand: case, a trailing slash, the default port and spaces don't matter. */
    @ParameterizedTest
    @ValueSource(strings = {
        "https://beacon.example",
        "HTTPS://Beacon.Example",
        "https://beacon.example/",
        "https://beacon.example:443",
        "  https://beacon.example  ",
    })
    void allowListEntries_areNormalized(String entry) {
        assertThat(allowing(entry).check(request("http", "app", 8080, "Origin", "https://beacon.example")))
                .isEqualTo(Verdict.ALLOWED);
    }

    /** An empty environment variable binds to an empty list, or a list of blanks: both mean "not configured". */
    @Test
    void allowListOfOnlyBlankEntries_fallsBackToTheRequestsOwnOrigin() {
        SameOriginGuard guard = allowing("", "  ");

        assertThat(guard.check(localhost8080("Origin", "http://localhost:8080"))).isEqualTo(Verdict.ALLOWED);
    }

    @ParameterizedTest
    @NullSource
    void allowListNotBound_fallsBackToTheRequestsOwnOrigin(List<String> origins) {
        SameOriginGuard guard = new SameOriginGuard(new SameOriginProperties(origins));

        assertThat(guard.check(localhost8080("Origin", "http://localhost:8080"))).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void allowList_withABlankEntryAmongRealOnes_ignoresTheBlank() {
        List<String> origins = new ArrayList<>(List.of("https://beacon.example"));
        origins.add("");

        SameOriginGuard guard = new SameOriginGuard(new SameOriginProperties(origins));

        assertThat(guard.check(request("http", "app", 8080, "Origin", "https://beacon.example")))
                .isEqualTo(Verdict.ALLOWED);
        assertThat(guard.check(localhost8080("Origin", "http://localhost:8080"))).isEqualTo(Verdict.OTHER_ORIGIN);
    }

    /** A typo must stop the app at startup, not silently refuse every request. */
    @ParameterizedTest
    @ValueSource(strings = {
        "beacon.example",
        "https://",
        "https://beacon.example/gallery",
        "https://beacon.example?x=1",
        "https://user@beacon.example",
        "ftp://beacon.example",
        "null",
        "*",
    })
    void invalidAllowListEntry_failsFast_namingTheEntry(String entry) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> allowing(entry))
                .withMessageContaining("beacon.web.allowed-origins")
                .withMessageContaining(entry);
    }

    @Test
    void verdicts_onlyAllowedIsAllowed() {
        assertThat(Arrays.stream(Verdict.values()).filter(Verdict::allowed)).containsExactly(Verdict.ALLOWED);
    }
}
