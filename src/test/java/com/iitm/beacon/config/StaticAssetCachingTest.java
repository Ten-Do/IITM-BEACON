package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

/**
 * The site's stylesheets and scripts are served at content-versioned URLs —
 * {@code /css/beacon-<md5 of the file>.css}, and a WebJar's at its versioned
 * path ({@code /webjars/alpinejs/<version>/…}) — which the pages render and
 * which a browser may keep for a year without asking again ({@code
 * max-age=31536000, private, immutable}): a changed file gets a new URL. The
 * plain URLs still work but are revalidated on every use ({@code no-cache}),
 * since what they name can change; pages and the JSON API stay {@code
 * no-store}. {@code private}: every response that finds no {@code
 * XSRF-TOKEN} cookie in its request sets one (decision 32), so no shared
 * cache may keep these responses and hand that token to others.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class StaticAssetCachingTest {

    static final String IMMUTABLE = "max-age=31536000, private, immutable";
    static final String REVALIDATE = "no-cache, private";
    private static final String NO_STORE = "no-cache, no-store, max-age=0, must-revalidate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResourceUrlProvider resourceUrls;

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String versioned(String path) {
        String url = resourceUrls.getForLookupPath(path);
        assertThat(url).as("versioned URL of " + path).isNotNull().isNotEqualTo(path);
        return url;
    }

    // -- the URLs --

    @ParameterizedTest
    @ValueSource(strings = {
        "/css/beacon.css", "/css/noscript.css", "/js/nav-toggle.js", "/js/photo-viewer.js", "/js/session-check.js",
        "/js/contact-reveal.js", "/js/submission-form.js"
    })
    void ownFile_isVersionedByTheMd5OfItsContent(String path) throws Exception {
        byte[] content = perform(get(path)).getContentAsByteArray();
        String extension = path.substring(path.lastIndexOf('.'));

        assertThat(versioned(path)).isEqualTo(
                path.substring(0, path.lastIndexOf('.')) + "-" + DigestUtils.md5DigestAsHex(content) + extension);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/webjars/alpinejs/dist/cdn.min.js", "/webjars/photoswipe/dist/photoswipe.css"})
    void webJarFile_isVersionedByItsWebJarsVersion(String path) throws Exception {
        assertThat(versioned(path)).matches("/webjars/[a-z]+/\\d[^/]*/dist/[^/]+");
    }

    @Test
    void everyPage_rendersTheSharedStylesheetAndScriptAtTheirVersionedUrls() throws Exception {
        String html = perform(get("/")).getContentAsString();

        assertThat(html)
                .contains("href=\"" + versioned("/css/beacon.css") + "\"")
                .contains("src=\"" + versioned("/js/nav-toggle.js") + "\"")
                .doesNotContain("\"/css/beacon.css\"", "\"/js/nav-toggle.js\"");
    }

    @Test
    void submissionForm_rendersItsScriptsAndAlpineAtTheirVersionedUrls() throws Exception {
        var visitor = new UsernamePasswordAuthenticationToken(
                "asset-urls@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));

        String html = perform(get("/submissions/form").with(authentication(visitor))).getContentAsString();

        assertThat(html).contains(
                "src=\"" + versioned("/js/submission-form.js") + "\"",
                "src=\"" + versioned("/webjars/alpinejs/dist/cdn.min.js") + "\"",
                "src=\"" + versioned("/js/session-check.js") + "\"",
                "href=\"" + versioned("/css/noscript.css") + "\"");
    }

    // -- the cache headers --

    @ParameterizedTest
    @ValueSource(strings = {"/css/beacon.css", "/js/photo-viewer.js", "/webjars/alpinejs/dist/cdn.min.js",
        "/webjars/photoswipe/dist/photoswipe-lightbox.esm.min.js"})
    void versionedUrl_isCachedForAYear_asImmutable(String path) throws Exception {
        MockHttpServletResponse response = perform(get(versioned(path)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders("Cache-Control")).containsExactly(IMMUTABLE);
        assertThat(response.getHeader("Pragma")).isNullOrEmpty();
        assertThat(response.getHeader("Expires")).isNullOrEmpty();
    }

    @Test
    void versionedUrl_servesTheFileItself() throws Exception {
        assertThat(perform(get(versioned("/js/nav-toggle.js"))).getContentAsByteArray())
                .isEqualTo(perform(get("/js/nav-toggle.js")).getContentAsByteArray());
    }

    /** The viewer module imports PhotoSwipe at its plain WebJar URL: it still loads, revalidated each time. */
    @ParameterizedTest
    @ValueSource(strings = {"/css/beacon.css", "/js/photo-viewer.js", "/webjars/alpinejs/dist/cdn.min.js",
        "/webjars/photoswipe/dist/photoswipe-lightbox.esm.min.js", "/webjars/photoswipe/dist/photoswipe.esm.min.js"})
    void plainUrl_stillServesTheFile_butMustBeRevalidated(String path) throws Exception {
        MockHttpServletResponse response = perform(get(path));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders("Cache-Control")).containsExactly(REVALIDATE);
        assertThat(response.getHeader("Last-Modified")).isNotBlank();
    }

    /** A hash that isn't the file's (an old or forged URL) names nothing, and nothing is cached for it. */
    @ParameterizedTest
    @ValueSource(strings = {
        "/css/beacon-0123456789abcdef0123456789abcdef.css",
        "/js/nav-toggle-0123456789abcdef0123456789abcdef.js",
        "/js/no-such-file-0123456789abcdef0123456789abcdef.js",
        "/webjars/alpinejs/0.0.1/dist/cdn.min.js"
    })
    void urlWithAVersionThatIsNotTheFiles_isA404_neverCached(String path) throws Exception {
        MockHttpServletResponse response = perform(get(path));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getHeaders("Cache-Control")).containsExactly(NO_STORE);
    }

    @Test
    void uppercaseSpellingOfTheHash_isNotTheVersionedUrl() throws Exception {
        String versioned = versioned("/css/beacon.css");
        String hash = versioned.substring(versioned.lastIndexOf('-') + 1, versioned.lastIndexOf('.'));

        MockHttpServletResponse response = perform(get(versioned.replace(hash, hash.toUpperCase())));

        assertThat(response.getHeaders("Cache-Control")).doesNotContain(IMMUTABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/gallery", "/api/gallery/countries"})
    void pagesAndApi_areStillNeverStored(String path) throws Exception {
        MockHttpServletResponse response = perform(get(path));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders("Cache-Control")).containsExactly(NO_STORE);
    }
}
