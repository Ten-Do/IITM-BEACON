package com.iitm.beacon.submission;

/**
 * One upload after {@link PhotoImageProcessor}: the full-size and thumbnail
 * lossy WebP encodings, and the full-size image's pixel dimensions (after
 * EXIF orientation). The byte arrays are copied in and out, so an instance
 * can't be changed after the fact.
 */
public record ProcessedPhoto(byte[] fullWebp, byte[] thumbWebp, int width, int height) {

    public ProcessedPhoto {
        fullWebp = fullWebp.clone();
        thumbWebp = thumbWebp.clone();
    }

    @Override
    public byte[] fullWebp() {
        return fullWebp.clone();
    }

    @Override
    public byte[] thumbWebp() {
        return thumbWebp.clone();
    }
}
