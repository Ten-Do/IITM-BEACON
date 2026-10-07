package com.iitm.beacon.testsupport;

import java.util.regex.Pattern;

/**
 * The site's stylesheets and scripts are rendered at content-versioned URLs
 * ({@code /css/beacon-<md5>.css}) and WebJar files at their WebJar's
 * versioned path ({@code /webjars/alpinejs/<version>/…}) —
 * {@code config.StaticAssetsConfig}. For tests that check which file a page
 * loads, not its version.
 */
public final class AssetUrls {

    private static final Pattern CONTENT_VERSION = Pattern.compile("-[0-9a-f]{32}(\\.\\w+)$");
    private static final Pattern WEBJAR_VERSION = Pattern.compile("^(/webjars/[^/]+)/\\d[^/]*(/.+)$");

    private AssetUrls() {
    }

    /** {@code url} without its version: the path the template links. */
    public static String plain(String url) {
        String withoutContentVersion = CONTENT_VERSION.matcher(url).replaceFirst("$1");
        return WEBJAR_VERSION.matcher(withoutContentVersion).replaceFirst("$1$2");
    }
}
