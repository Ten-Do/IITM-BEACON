package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.testsupport.TestImages;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Stored photos are WebP files; the embedded Tomcat (not MockMvc) must serve
 * them as {@code image/webp} — with Spring Security's {@code
 * X-Content-Type-Options: nosniff}, a generic type would stop browsers from
 * rendering them at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadsContentTypeOverHttpTest {

    @TempDir
    static Path uploadsRoot;

    @DynamicPropertySource
    static void overrideStorageRoot(DynamicPropertyRegistry registry) {
        registry.add("beacon.storage.root-path", () -> uploadsRoot.toString());
    }

    @LocalServerPort
    private int port;

    @Test
    void webpPhoto_isServedAsImageWebp_withNosniff() throws Exception {
        byte[] webp = TestImages.webpLossless(TestImages.solid(4, 4, TestImages.RED));
        Files.write(uploadsRoot.resolve("photo.webp"), webp);

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/uploads/photo.webp")).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("image/webp");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.body()).isEqualTo(webp);
    }

    /**
     * A stored photo never changes under its random name (decision 2: files
     * are never overwritten), so a browser keeps it for a year without asking
     * again — {@code private}: no shared cache keeps serving a photo after
     * it is deleted (a removed photo, a purged rejected testimonial).
     */
    @Test
    void storedPhoto_isCachedForAYear_asImmutable_byTheBrowserOnly() throws Exception {
        byte[] webp = TestImages.webpLossless(TestImages.solid(4, 4, TestImages.RED));
        Files.write(uploadsRoot.resolve("cached.webp"), webp);

        HttpResponse<byte[]> response = get("/uploads/cached.webp");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Cache-Control"))
                .containsExactly("max-age=31536000, private, immutable");
        assertThat(response.headers().firstValue("Pragma")).isEmpty();
        assertThat(response.headers().firstValue("Expires")).isEmpty();
    }

    /** A photo that isn't there (never was, or deleted) is not cached: it may be a stale link. */
    @Test
    void missingPhoto_isA404_neverCached() throws Exception {
        HttpResponse<byte[]> response = get("/uploads/no-such-photo.webp");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().allValues("Cache-Control"))
                .containsExactly("no-cache, no-store, max-age=0, must-revalidate");
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
