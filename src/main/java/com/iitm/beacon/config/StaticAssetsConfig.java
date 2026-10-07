package com.iitm.beacon.config;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.VersionResourceResolver;

/**
 * Serves the site's own stylesheets and scripts ({@code /css/**}, {@code
 * /js/**}) and its WebJars ({@code /webjars/**}) so a browser downloads each
 * version of a file once.
 *
 * <p>Every file has a versioned URL: the site's own files carry the MD5 of
 * their content in the name ({@code /css/beacon-<md5>.css}, Spring's content
 * {@link VersionResourceResolver}), a WebJar's file the WebJar's version in
 * its path ({@code /webjars/alpinejs/<version>/dist/cdn.min.js}, resolved by
 * the WebJars locator). A template's link expression ({@code
 * th:href="@{/css/beacon.css}"}) is rendered as that URL, and a versioned
 * URL answers {@code max-age=31536000, private, immutable}: a changed file
 * gets a new URL, so the old one can be kept for a year. The plain URLs
 * still work — a module's import at run time ({@code static/js/photo-viewer.js}
 * imports PhotoSwipe at its version-less URL) — but name whatever the file
 * is now, so they answer {@code no-cache, private}: revalidated on every
 * use. A URL whose version isn't the file's is a 404, never cached.
 *
 * <p>{@code private}: the response to a request without an {@code
 * XSRF-TOKEN} cookie sets one (decision 32), so a shared cache must not keep
 * these responses and hand that token to everyone else.
 *
 * <p>Registered before Spring Boot's own resource handlers, which then leave
 * {@code /webjars/**} to this class; pages and the JSON API keep Spring
 * Security's {@code no-store}.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StaticAssetsConfig implements WebMvcConfigurer {

    /** For a URL that names one version of a file. */
    static final CacheControl VERSIONED = CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();

    /** For a URL that names whatever the file is now. */
    static final CacheControl UNVERSIONED = CacheControl.noCache().cachePrivate();

    /**
     * A file name carrying a content version: {@code <name>-<32 lowercase hex
     * digits>.<extension>} (a pattern variable's regex sees one path segment
     * and must not itself contain a {@code /}).
     */
    private static final String CONTENT_VERSIONED_FILE = "{file:.+-[0-9a-f]{32}\\.\\w+}";

    /** A WebJar's versioned path: {@code /webjars/<library>/<version, starting with a digit>/…}. */
    private static final String VERSIONED_WEBJAR = "/webjars/{library}/{version:\\d.*}/**";

    private static final String WEBJARS_LOCATION = "classpath:/META-INF/resources/webjars/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        for (String directory : new String[] {"css", "js"}) {
            String location = "classpath:/static/" + directory + "/";
            registry.addResourceHandler("/" + directory + "/" + CONTENT_VERSIONED_FILE)
                    .addResourceLocations(location)
                    .setCacheControl(VERSIONED)
                    .resourceChain(true)
                    .addResolver(contentVersions());
            registry.addResourceHandler("/" + directory + "/**")
                    .addResourceLocations(location)
                    .setCacheControl(UNVERSIONED)
                    .resourceChain(true)
                    .addResolver(contentVersions());
        }
        // The chain adds the WebJars locator's resolver on its own.
        registry.addResourceHandler(VERSIONED_WEBJAR)
                .addResourceLocations(WEBJARS_LOCATION)
                .setCacheControl(VERSIONED)
                .resourceChain(true);
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations(WEBJARS_LOCATION)
                .setCacheControl(UNVERSIONED)
                .resourceChain(true);
    }

    private static VersionResourceResolver contentVersions() {
        return new VersionResourceResolver().addContentVersionStrategy("/**");
    }
}
