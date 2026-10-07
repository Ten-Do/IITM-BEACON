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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Best-effort removal of photo files from the uploads root, shared by every
 * slice that deletes photos (submission edits, catalog deletes — decision
 * 28 — and the rejected-testimonial purge, decision 3). It never throws, and
 * reports whether every file was there and is now gone, so the purge can
 * flag a file that was already missing. Real temp directories, no mocks.
 */
class PhotoFileDeleterTest {

    @TempDir
    Path root;

    private PhotoFileDeleter deleter() {
        return new PhotoFileDeleter(TestPhotoStorage.properties(root));
    }

    // -- delete(Photo) --

    @Test
    void deletePhoto_removesBothTheFullSizeFileAndTheThumbnail_andReportsSuccess() throws Exception {
        Files.writeString(root.resolve("a.webp"), "x");
        Files.writeString(root.resolve("a-thumb.webp"), "x");

        boolean deleted = deleter().delete(photo("a.webp", "a-thumb.webp"));

        assertThat(deleted).isTrue();
        assertThat(rootContents()).isEmpty();
    }

    @Test
    void deletePhoto_legacyPhotoWithoutThumbnail_removesItsFile_andReportsSuccess() throws Exception {
        Files.writeString(root.resolve("legacy.jpeg"), "x");

        boolean deleted = deleter().delete(photo("legacy.jpeg", null));

        assertThat(deleted).isTrue();
        assertThat(rootContents()).isEmpty();
    }

    @Test
    void deletePhoto_bothFilesAlreadyGone_reportsFailure_andDoesNotThrow() {
        PhotoFileDeleter deleter = deleter();

        assertThatCode(() -> assertThat(deleter.delete(photo("never.webp", "never-thumb.webp"))).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void deletePhoto_onlyTheThumbnailAlreadyGone_stillRemovesTheFullSizeFile_andReportsFailure() throws Exception {
        Files.writeString(root.resolve("half.webp"), "x");

        boolean deleted = deleter().delete(photo("half.webp", "half-thumb.webp"));

        assertThat(deleted).isFalse();
        assertThat(rootContents()).isEmpty();
    }

    @Test
    void deletePhoto_onlyTheFullSizeFileAlreadyGone_stillRemovesTheThumbnail_andReportsFailure() throws Exception {
        Files.writeString(root.resolve("half-thumb.webp"), "x");

        boolean deleted = deleter().delete(photo("half.webp", "half-thumb.webp"));

        assertThat(deleted).isFalse();
        assertThat(rootContents()).isEmpty();
    }

    @Test
    void deletePhoto_legacyPhotoWhoseFileIsAlreadyGone_reportsFailure() {
        assertThat(deleter().delete(photo("gone.jpeg", null))).isFalse();
    }

    @Test
    void deletePhoto_fullSizeFileCannotBeDeleted_stillRemovesTheThumbnail_reportsFailure_andDoesNotThrow()
            throws Exception {
        // A non-empty directory under the file's name makes deleteIfExists fail with an IOException.
        Files.createDirectories(root.resolve("stuck.webp").resolve("inner"));
        Files.writeString(root.resolve("stuck-thumb.webp"), "x");
        PhotoFileDeleter deleter = deleter();

        assertThatCode(() -> assertThat(deleter.delete(photo("stuck.webp", "stuck-thumb.webp"))).isFalse())
                .doesNotThrowAnyException();

        assertThat(rootContents()).containsExactly("stuck.webp");
    }

    @Test
    void deletePhoto_fullSizePathIsNotAValidPath_stillRemovesTheThumbnail_reportsFailure_andDoesNotThrow()
            throws Exception {
        Files.writeString(root.resolve("odd-thumb.webp"), "x");
        PhotoFileDeleter deleter = deleter();

        assertThatCode(() -> assertThat(deleter.delete(photo("odd\0.webp", "odd-thumb.webp"))).isFalse())
                .doesNotThrowAnyException();

        assertThat(rootContents()).isEmpty();
    }

    // -- delete(String) --

    @Test
    void deletePath_removesOnlyThatFile_andReportsSuccess() throws Exception {
        Files.writeString(root.resolve("a.webp"), "x");
        Files.writeString(root.resolve("a-thumb.webp"), "x");

        boolean deleted = deleter().delete("a.webp");

        assertThat(deleted).isTrue();
        assertThat(rootContents()).containsExactly("a-thumb.webp");
    }

    @Test
    void deletePath_fileAlreadyGone_reportsFailure() {
        assertThat(deleter().delete("never.webp")).isFalse();
    }

    @Test
    void deletePath_fileCannotBeDeleted_reportsFailure_andDoesNotThrow() throws Exception {
        Files.createDirectories(root.resolve("stuck.webp").resolve("inner"));
        PhotoFileDeleter deleter = deleter();

        assertThatCode(() -> assertThat(deleter.delete("stuck.webp")).isFalse()).doesNotThrowAnyException();
    }

    /** A NUL character can't appear in a file name: {@code Path.resolve} rejects it with an unchecked exception. */
    @Test
    void deletePath_notAValidPath_reportsFailure_andDoesNotThrow() {
        PhotoFileDeleter deleter = deleter();

        assertThatCode(() -> assertThat(deleter.delete("bad\0name.webp")).isFalse()).doesNotThrowAnyException();
    }

    /** An empty path would resolve to the uploads root itself, which must never be deleted. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void deletePath_blank_reportsFailure_andLeavesTheEmptyUploadsRootInPlace(String blank) {
        boolean deleted = deleter().delete(blank);

        assertThat(deleted).isFalse();
        assertThat(root).isDirectory();
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
