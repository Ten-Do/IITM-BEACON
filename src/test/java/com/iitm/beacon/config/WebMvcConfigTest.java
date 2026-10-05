package com.iitm.beacon.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.testsupport.TestImages;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Confirms {@code /uploads/**} is served, unauthenticated, straight off the
 * configured {@code beacon.storage.root-path} directory (decision 2,
 * docs/architecture.md §7) — a plain static resource handler, not a
 * dedicated per-photo endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class WebMvcConfigTest {

    @TempDir
    static Path uploadsRoot;

    @DynamicPropertySource
    static void overrideStorageRoot(DynamicPropertyRegistry registry) {
        registry.add("beacon.storage.root-path", () -> uploadsRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesFileFromConfiguredUploadsRootWithoutAuthentication() throws Exception {
        Files.writeString(uploadsRoot.resolve("hello.txt"), "hello world");

        mockMvc.perform(get("/uploads/hello.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello world"));
    }

    @Test
    void servesWebpPhotosAsImageWebp() throws Exception {
        byte[] webp = TestImages.webpLossless(TestImages.solid(4, 4, TestImages.RED));
        Files.write(uploadsRoot.resolve("photo.webp"), webp);
        Files.write(uploadsRoot.resolve("photo-thumb.webp"), webp);

        mockMvc.perform(get("/uploads/photo.webp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(content().bytes(webp));
        mockMvc.perform(get("/uploads/photo-thumb.webp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"));
    }

    @Test
    void returns404ForFileNotOnDisk() throws Exception {
        mockMvc.perform(get("/uploads/does-not-exist.txt")).andExpect(status().isNotFound());
    }
}
