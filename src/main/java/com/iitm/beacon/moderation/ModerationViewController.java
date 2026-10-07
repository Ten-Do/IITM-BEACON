package com.iitm.beacon.moderation;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.NotificationNotSentException;
import com.iitm.beacon.common.error.TestimonialNotPendingException;
import com.iitm.beacon.common.web.PageRequests;
import com.iitm.beacon.common.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Thymeleaf view layer for the admin moderation queue (UC-VIEW-PENDING-QUEUE,
 * UC-APPROVE-TESTIMONIAL, UC-REJECT-TESTIMONIAL). Delegates all business
 * logic to {@link ModerationService} — the JSON API in {@link
 * ModerationController} already covers the same use cases for API
 * consumers; this controller only renders/drives the HTML form flow.
 *
 * <p>The two POST actions deliberately catch {@link NotFoundException} and
 * {@link TestimonialNotPendingException} themselves rather than letting them
 * reach {@code GlobalExceptionHandler} (whose answer to a page is the site's
 * generic error page — a dead end for a form submission). Both cases
 * collapse to the same redirect: the item is simply gone from the queue,
 * which is self-explanatory once the page reloads.
 *
 * <p>A reject whose email to the submitter couldn't be sent ({@link
 * NotificationNotSentException}) changed nothing — the item is still in the
 * queue — so that one needs saying: the redirect carries the service's
 * message as a flash error, and the reason the admin typed, which the queue
 * puts back into that item's reason field for the retry.
 */
@Controller
@RequestMapping("/moderation")
public class ModerationViewController {

    private static final Logger log = LoggerFactory.getLogger(ModerationViewController.class);
    private static final int PAGE_SIZE = 20;
    private static final String QUEUE_VIEW = "moderation/queue";
    private static final String REDIRECT_TO_QUEUE = "redirect:/moderation/queue";

    private final ModerationService moderationService;

    public ModerationViewController(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @GetMapping("/queue")
    public String queue(@RequestParam(defaultValue = "0") int page, Model model) {
        int safePage = Math.max(page, 0);
        PageResponse<ModerationQueueCardDto> result =
                moderationService.listPendingForView(PageRequests.of(safePage, PAGE_SIZE));
        model.addAttribute("queue", result);
        return QUEUE_VIEW;
    }

    @PostMapping("/queue/{id}/approve")
    public String approve(@PathVariable Long id) {
        try {
            moderationService.approve(id);
        } catch (NotFoundException | TestimonialNotPendingException ex) {
            log.info("Approve skipped for testimonial {}: {}", id, ex.getMessage());
        }
        return REDIRECT_TO_QUEUE;
    }

    @PostMapping("/queue/{id}/reject")
    public String reject(
            @PathVariable Long id,
            @RequestParam(required = false) String reason,
            RedirectAttributes redirectAttributes) {
        try {
            moderationService.reject(id, reason);
        } catch (NotFoundException | TestimonialNotPendingException ex) {
            log.info("Reject skipped for testimonial {}: {}", id, ex.getMessage());
        } catch (NotificationNotSentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            redirectAttributes.addFlashAttribute("rejectFailedId", id);
            redirectAttributes.addFlashAttribute("rejectReason", reason);
        }
        return REDIRECT_TO_QUEUE;
    }
}
