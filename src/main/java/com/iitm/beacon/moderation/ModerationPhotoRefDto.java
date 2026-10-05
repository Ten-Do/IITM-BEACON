package com.iitm.beacon.moderation;

import java.util.List;

/**
 * A photo reference within a {@link ModerationSectionViewDto} (decision 2) —
 * {@code url} (full size) and {@code thumbnailUrl} are resolved via {@code
 * config.PhotoUrlResolver}; a legacy photo stored before thumbnails existed
 * has its full-size {@code url} as {@code thumbnailUrl}, and {@code null}
 * {@code width}/{@code height}.
 */
public record ModerationPhotoRefDto(
        String url, String thumbnailUrl, Integer width, Integer height, List<String> tags) {
}
