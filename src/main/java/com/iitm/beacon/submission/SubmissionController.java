package com.iitm.beacon.submission;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * Create/edit-testimonial endpoints (UC-CREATE-TESTIMONIAL,
 * UC-EDIT-TESTIMONIAL) plus the two public reference-data listings
 * (decisions 5, 20). Role/method authorization lives in {@code
 * config.SecurityConfig}; the visitor's email comes from the session
 * principal established at OTP verify (decision 17), same convention as
 * {@code VisitorAuthController}.
 */
@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @GetMapping("/contact-types")
    public List<ContactTypeView> contactTypes() {
        return submissionService.listActiveContactTypes();
    }

    @GetMapping("/achievements")
    public List<AchievementView> achievements() {
        return submissionService.listActiveAchievements();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionResultResponse create(
            @RequestPart("payload") @Valid TestimonialSubmissionRequest payload,
            MultipartHttpServletRequest request,
            Authentication authentication) {
        String email = (String) authentication.getPrincipal();
        return submissionService.create(email, payload, request.getFileMap());
    }

    @GetMapping("/mine")
    public TestimonialSubmissionView mine(Authentication authentication) {
        String email = (String) authentication.getPrincipal();
        return submissionService.loadMine(email);
    }

    @PutMapping(path = "/mine", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SubmissionResultResponse editMine(
            @RequestPart("payload") @Valid TestimonialSubmissionRequest payload,
            MultipartHttpServletRequest request,
            Authentication authentication) {
        String email = (String) authentication.getPrincipal();
        return submissionService.edit(email, payload, request.getFileMap());
    }
}
