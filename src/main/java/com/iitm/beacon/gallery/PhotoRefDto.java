package com.iitm.beacon.gallery;

import java.util.List;

/**
 * A photo attached to a testimonial section, as rendered publicly (decision
 * 2): {@code url} is the full-size image, {@code thumbnailUrl} its thumbnail
 * — the full-size {@code url} again for a legacy photo stored before
 * thumbnails existed — and {@code width}/{@code height} are the full-size
 * image's pixel dimensions ({@code null} when not known for a legacy photo).
 */
public record PhotoRefDto(String url, String thumbnailUrl, Integer width, Integer height, List<String> tags) {
}
