package com.iitm.beacon.moderation;

import java.util.List;

/**
 * A photo reference within a {@link ModerationSectionViewDto} (decision 2) —
 * {@code url} is resolved via {@code config.PhotoUrlResolver}.
 */
public record ModerationPhotoRefDto(String url, List<String> tags) {
}
