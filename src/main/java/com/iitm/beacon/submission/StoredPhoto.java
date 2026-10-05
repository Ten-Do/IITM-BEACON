package com.iitm.beacon.submission;

/**
 * Where one converted photo landed on the uploads volume (paths relative to
 * its root, as stored on {@code Photo}), plus the full-size image's pixel
 * dimensions.
 */
public record StoredPhoto(String filePath, String thumbnailPath, int width, int height) {
}
