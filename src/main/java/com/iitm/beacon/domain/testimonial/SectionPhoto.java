package com.iitm.beacon.domain.testimonial;

/**
 * One photo of a section, as stored (decision 2) — projection of {@link
 * PhotoRepository#findSectionPhotosBySectionIdIn}. A legacy photo stored
 * before thumbnails existed has no {@code thumbnailPath}, {@code width} or
 * {@code height}.
 */
public record SectionPhoto(
        Long id, Long sectionId, String filePath, String thumbnailPath, Integer width, Integer height) {
}
