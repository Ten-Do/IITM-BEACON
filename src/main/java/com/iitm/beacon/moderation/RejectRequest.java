package com.iitm.beacon.moderation;

/**
 * Optional request body for {@code POST /api/moderation/testimonials/{id}/reject}
 * (UC-REJECT-TESTIMONIAL). {@code reason} may be {@code null} or blank — the
 * rejection email varies its wording accordingly.
 */
public record RejectRequest(String reason) {
}
