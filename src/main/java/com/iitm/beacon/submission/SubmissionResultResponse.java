package com.iitm.beacon.submission;

import com.iitm.beacon.domain.testimonial.TestimonialStatus;

/**
 * Response shape for {@code POST /submissions} and {@code PUT
 * /submissions/mine} (decision 18: status reflects whether the moderation
 * short-circuit applied).
 */
public record SubmissionResultResponse(Long id, TestimonialStatus status) {
}
