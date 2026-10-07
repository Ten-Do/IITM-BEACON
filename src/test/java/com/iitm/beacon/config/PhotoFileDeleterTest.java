package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Best-effort removal of photo files from the uploads root, shared by every
 * slice that deletes photos (submission edits, catalog deletes — decision
 * 28). Real temp directories, no mocks.
 */
class PhotoFileDeleterTest {

    @TempDir
    Path root;

    private PhotoFileDeleter deleter() {
        return new PhotoFileDeleter(TestPhotoStorage.properties(root));
    }

    // -- delete(Photo) --

    @Test
    void deletePhoto_removesBothTheFullSizeFileAndTheThumbnail() throws Exception {
        Files.writeString(root.resolve("a.webp"), "x");
        Files.writeString(root.resolve("a-thumb.webp"), "x");

        deleter().delete(photo("a.webp", "a-thumb.webp"));

        assertThat(rootContents()).isEmpty();
    }

    @Test
    void deletePhoto_legacyPhotoWithoutThumbnail_removesItsFile() throws Exception {
        Files.writeString(root.resolve("legacy.jpeg"), "x");

        deleter().delete(photo("legacy.jpeg", null));

        assertThat(rootContents()).isEmpty();
    }

    @Test
    void deletePhoto_bothFilesAlreadyGone_doesNotThrow() {
        PhotoFileDeleter deleter = deleter();

        assertThatCode(() -> deleter.delete(photo("never.webp", "never-thumb.webp"))).doesNotThrowAnyException();
    }

    @Test
    void deletePhoto_fullSizeFileCannotBeDeleted_stillRemovesTheThumbnail_andDoesNotThrow() throws Exception {
        // A non-empty directory under the file's name makes deleteIfExists fail with an IOException.
        Files.createDirectories(root.resolve("stuck.webp").resolve("inner"));
        Files.writeString(root.resolve("stuck-thumb.webp"), "x");
        PhotoFileDeleter deleter = deleter();

        assertThatCode(() -> deleter.delete(photo("stuck.webp", "stuck-thumb.webp"))).doesNotThrowAnyException();

        assertThat(rootContents()).containsExactly("stuck.webp");
    }

    // -- delete(String) --

    @Test
    void deletePath_removesOnlyThatFile() throws Exception {
        Files.writeString(root.resolve("a.webp"), "x");
        Files.writeString(root.resolve("a-thumb.webp"), "x");

        deleter().delete("a.webp");

        assertThat(rootContents()).containsExactly("a-thumb.webp");
    }

    private static Photo photo(String filePath, String thumbnailPath) {
        return Photo.builder().filePath(filePath).thumbnailPath(thumbnailPath).displayOrder(0).build();
    }

    private List<String> rootContents() throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }
}
