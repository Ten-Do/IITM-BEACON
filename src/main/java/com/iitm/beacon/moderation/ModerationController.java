package com.iitm.beacon.moderation;

import com.iitm.beacon.common.web.PageRequests;
import com.iitm.beacon.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin moderation endpoints (UC-VIEW-PENDING-QUEUE, UC-APPROVE-TESTIMONIAL,
 * UC-REJECT-TESTIMONIAL), plus the admin session ping. Role enforcement
 * lives in {@code config.SecurityConfig} (added in a later batch), same
 * convention as {@code SubmissionController}. The pending list's paging is
 * validated against its documented bounds (page ≥ 0, size 1–100, and page ×
 * size within an {@code int} — {@link PageRequests}), like the gallery's: a
 * value outside them is a 400, never a {@code PageRequest} that can't be
 * built or a query that fails.
 */
@RestController
@RequestMapping("/api/moderation")
@Validated
public class ModerationController {

    private final ModerationService moderationService;

    public ModerationController(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @GetMapping("/testimonials/pending")
    public PageResponse<ModerationTestimonialDetailDto> pending(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return moderationService.listPending(PageRequests.of(page, size));
    }

    @PostMapping("/testimonials/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable Long id) {
        moderationService.approve(id);
    }

    @PostMapping("/testimonials/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest request) {
        String reason = request != null ? request.reason() : null;
        moderationService.reject(id, reason);
    }

    /**
     * Session ping for {@code static/js/session-check.js} on the admin's
     * pages: 204 while the admin session is alive (the request itself keeps
     * it alive), otherwise the security layer's JSON 401 (no or expired
     * session) or 403 (another role's session), which makes the script
     * reload the page and so go through the login redirect.
     */
    @GetMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void session() {
        // Reaching this method is the whole answer: SecurityConfig already required an admin session.
    }
}
