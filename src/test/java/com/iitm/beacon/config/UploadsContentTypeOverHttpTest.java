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
}
