package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@link AssetUrls#plain}: a rendered asset URL back to the path a template links. */
class AssetUrlsTest {

    @ParameterizedTest
    @CsvSource({
        "/css/beacon-0123456789abcdef0123456789abcdef.css, /css/beacon.css",
        "/js/nav-toggle-0123456789abcdef0123456789abcdef.js, /js/nav-toggle.js",
        "/js/photo-viewer.js, /js/photo-viewer.js",
        "/webjars/alpinejs/3.17.4/dist/cdn.min.js, /webjars/alpinejs/dist/cdn.min.js",
        "/webjars/photoswipe/dist/photoswipe.css, /webjars/photoswipe/dist/photoswipe.css",
        "/js/short-0123abcd.js, /js/short-0123abcd.js",
        "/api/submissions/session, /api/submissions/session"
    })
    void plain_dropsTheVersionOnly(String rendered, String plain) {
        assertThat(AssetUrls.plain(rendered)).isEqualTo(plain);
    }
}
