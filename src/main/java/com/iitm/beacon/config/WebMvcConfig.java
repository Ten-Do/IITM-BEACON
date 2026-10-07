package com.iitm.beacon.config;

import com.iitm.beacon.common.web.SameOriginInterceptor;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves uploaded photos back to any client through a plain static resource
 * handler (decision 2, docs/architecture.md §7) — not a dedicated per-photo
 * endpoint, and not owned by any one feature slice — cached by the browser
 * for a year ({@link #STORED_PHOTO}).
 *
 * <p>Also registers {@link SameOriginInterceptor} for every path: it only
 * acts on handlers marked {@code common.web.SameOriginOnly}, so the marker on
 * a controller method is the single place that decides which endpoints it
 * guards.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * A stored photo never changes under its random name (files are never
     * overwritten, decision 2): the browser keeps it for a year without
     * asking again. {@code private}: no shared cache keeps serving it after
     * the photo is deleted — and the response may set the CSRF cookie
     * (decision 32), which must not be shared either.
     */
    static final CacheControl STORED_PHOTO = CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();

    private final PhotoStorageProperties photoStorageProperties;
    private final SameOriginInterceptor sameOriginInterceptor;

    public WebMvcConfig(PhotoStorageProperties photoStorageProperties, SameOriginInterceptor sameOriginInterceptor) {
        this.photoStorageProperties = photoStorageProperties;
        this.sameOriginInterceptor = sameOriginInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + normalizedRootPath())
                .setCacheControl(STORED_PHOTO);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sameOriginInterceptor);
    }

    private String normalizedRootPath() {
        String rootPath = photoStorageProperties.rootPath();
        return rootPath.endsWith("/") ? rootPath : rootPath + "/";
    }
}
