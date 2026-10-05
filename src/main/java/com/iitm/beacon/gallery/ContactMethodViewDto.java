package com.iitm.beacon.gallery;

/**
 * One contact method as the article's contact card shows it
 * (UC-REVEAL-CONTACT, {@code gallery/contact-card.html}) — only entries
 * marked public (decisions 5, 6). Contrast with moderation's contact-method
 * view, which also carries {@code isPublic} and every entry, not just
 * public ones.
 */
public record ContactMethodViewDto(String type, String value) {
}
