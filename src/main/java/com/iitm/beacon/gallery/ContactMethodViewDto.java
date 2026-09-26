package com.iitm.beacon.gallery;

/**
 * Shape returned by the public contact-reveal endpoint — only entries marked
 * public (decisions 5, 6). Contrast with moderation's contact-method view,
 * which also carries {@code isPublic} and every entry, not just public ones.
 */
public record ContactMethodViewDto(String type, String value) {
}
