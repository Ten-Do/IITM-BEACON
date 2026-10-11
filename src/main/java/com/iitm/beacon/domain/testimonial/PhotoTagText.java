package com.iitm.beacon.domain.testimonial;

/** One tag of a photo — projection of {@link PhotoTagRepository#findTextsByPhotoIdIn}. */
public record PhotoTagText(Long photoId, String tagText) {
}
