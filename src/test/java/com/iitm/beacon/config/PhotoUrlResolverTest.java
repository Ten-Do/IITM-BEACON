package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhotoUrlResolverTest {

    private final PhotoUrlResolver resolver = new PhotoUrlResolver();

    @Test
    void resolve_prependsUploadsPrefix() {
        assertThat(resolver.resolve("abc123.png")).isEqualTo("/uploads/abc123.png");
    }

    @Test
    void resolve_emptyRelativePath_stillPrependsUploadsPrefix() {
        assertThat(resolver.resolve("")).isEqualTo("/uploads/");
    }
}
