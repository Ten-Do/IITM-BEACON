package com.iitm.beacon.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves uploaded photos back to any client through a plain static resource
 * handler (decision 2, docs/architecture.md §7) — not a dedicated per-photo
 * endpoint, and not owned by any one feature slice.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PhotoStorageProperties photoStorageProperties;

    public WebMvcConfig(PhotoStorageProperties photoStorageProperties) {
        this.photoStorageProperties = photoStorageProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations("file:" + normalizedRootPath());
    }

    private String normalizedRootPath() {
        String rootPath = photoStorageProperties.rootPath();
        return rootPath.endsWith("/") ? rootPath : rootPath + "/";
    }
}
