package com.iitm.beacon.moderation;

import com.iitm.beacon.common.web.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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
 * UC-REJECT-TESTIMONIAL). Role enforcement lives in {@code
 * config.SecurityConfig} (added in a later batch), same convention as {@code
 * SubmissionController}.
 */
@RestController
@RequestMapping("/api/moderation")
public class ModerationController {

    private final ModerationService moderationService;

    public ModerationController(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @GetMapping("/testimonials/pending")
    public PageResponse<ModerationTestimonialDetailDto> pending(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return moderationService.listPending(PageRequest.of(page, size));
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
}
