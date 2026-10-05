package com.iitm.beacon.config;

import com.iitm.beacon.common.web.SameOriginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves uploaded photos back to any client through a plain static resource
 * handler (decision 2, docs/architecture.md §7) — not a dedicated per-photo
 * endpoint, and not owned by any one feature slice.
 *
 * <p>Also registers {@link SameOriginInterceptor} for every path: it only
 * acts on handlers marked {@code common.web.SameOriginOnly}, so the marker on
 * a controller method is the single place that decides which endpoints it
 * guards.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PhotoStorageProperties photoStorageProperties;
    private final SameOriginInterceptor sameOriginInterceptor;

    public WebMvcConfig(PhotoStorageProperties photoStorageProperties, SameOriginInterceptor sameOriginInterceptor) {
        this.photoStorageProperties = photoStorageProperties;
        this.sameOriginInterceptor = sameOriginInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations("file:" + normalizedRootPath());
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
