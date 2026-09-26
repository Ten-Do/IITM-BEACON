package com.iitm.beacon.submission;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.common.error.TestimonialAlreadyExistsException;
import com.iitm.beacon.common.security.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Thymeleaf view layer for the {@code submission} slice: the two-step
 * visitor OTP login flow, the create/edit testimonial form, and the
 * confirmation page. Delegates all business logic to {@link
 * SubmissionService}/{@link VisitorOtpService} — the JSON API in {@link
 * SubmissionController}/{@link VisitorAuthController} already covers the
 * same use cases for API consumers; this controller only renders/drives the
 * HTML form flow.
 *
 * <p>The two POST handlers that can fail on a business rule ({@link
 * SubmissionValidationException}/{@link TestimonialAlreadyExistsException})
 * catch them locally and re-render the form with an error, rather than
 * letting them reach {@code GlobalExceptionHandler} (a {@code
 * @RestControllerAdvice}, which would write a JSON body — the wrong response
 * shape for a plain browser form submission) — same convention as {@code
 * moderation.ModerationViewController}.
 */
@Controller
@RequestMapping("/submissions")
public class SubmissionViewController {

    private static final String LOGIN_EMAIL_VIEW = "submission/login-email";
    private static final String LOGIN_CODE_VIEW = "submission/login-code";
    private static final String FORM_VIEW = "submission/form";
    private static final String CONFIRMATION_VIEW = "submission/confirmation";
    private static final String REDIRECT_TO_LOGIN_EMAIL = "redirect:/submissions/login";
    private static final String REDIRECT_TO_FORM = "redirect:/submissions/form";
    private static final String REDIRECT_TO_CONFIRMATION = "redirect:/submissions/confirmation";
    private static final String WRONG_CODE_MESSAGE =
            "That code is wrong or has expired. Check your email, or request a new one.";
    private static final String BLANK_EMAIL_MESSAGE = "Please enter your email address.";

    private final SubmissionService submissionService;
    private final VisitorOtpService visitorOtpService;
    private final SessionAuthenticator sessionAuthenticator;

    public SubmissionViewController(
            SubmissionService submissionService,
            VisitorOtpService visitorOtpService,
            SessionAuthenticator sessionAuthenticator) {
        this.submissionService = submissionService;
        this.visitorOtpService = visitorOtpService;
        this.sessionAuthenticator = sessionAuthenticator;
    }

    // -- login: email step --

    @GetMapping("/login")
    public String loginEmailForm() {
        return LOGIN_EMAIL_VIEW;
    }

    @PostMapping("/login")
    public String requestOtp(@RequestParam(required = false) String email, HttpServletRequest request, Model model) {
        if (email == null || email.isBlank()) {
            model.addAttribute("error", BLANK_EMAIL_MESSAGE);
            return LOGIN_EMAIL_VIEW;
        }
        visitorOtpService.requestOtp(email, request.getRemoteAddr());
        return "redirect:/submissions/login/code?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
    }

    // -- login: code step --

    @GetMapping("/login/code")
    public String loginCodeForm(@RequestParam(required = false, defaultValue = "") String email, Model model) {
        if (email.isBlank()) {
            return REDIRECT_TO_LOGIN_EMAIL;
        }
        model.addAttribute("email", email);
        return LOGIN_CODE_VIEW;
    }

    @PostMapping("/login/code")
    public String verifyOtp(
            @RequestParam String email,
            @RequestParam String code,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        VisitorOtpVerifyResult result = visitorOtpService.verify(email, code);
        if (result instanceof VisitorOtpVerifyResult.Rejected) {
            model.addAttribute("email", email);
            model.addAttribute("error", WRONG_CODE_MESSAGE);
            return LOGIN_CODE_VIEW;
        }
        var verified = (VisitorOtpVerifyResult.Verified) result;
        sessionAuthenticator.login(request, response, verified.email(), "VISITOR");
        return REDIRECT_TO_FORM;
    }

    // -- submission form --

    @GetMapping("/form")
    public String form(Authentication authentication, Model model) {
        String email = (String) authentication.getPrincipal();
        List<TopicCatalogEntryDto> topicCatalog = submissionService.listTopicCatalog();
        List<TopicFormRow> rows = buildTopicFormRows(topicCatalog);
        List<ContactTypeView> contactTypes = submissionService.listActiveContactTypes();

        SubmissionFormCommand command = new SubmissionFormCommand();
        boolean editing;
        Map<String, TestimonialSubmissionView.SectionView> existingSectionsBySlug;
        Map<String, TestimonialSubmissionView.ContactMethodView> existingContactsBySlug;
        try {
            TestimonialSubmissionView existing = submissionService.loadMine(email);
            existingSectionsBySlug = existing.sections().stream()
                    .collect(Collectors.toMap(
                            TestimonialSubmissionView.SectionView::topicSlug, s -> s, (a, b) -> a));
            existingContactsBySlug = existing.contactMethods().stream()
                    .collect(Collectors.toMap(
                            TestimonialSubmissionView.ContactMethodView::typeSlug, c -> c, (a, b) -> a));
            prefillCommand(command, existing);
            editing = true;
        } catch (NotFoundException ex) {
            existingSectionsBySlug = Map.of();
            existingContactsBySlug = Map.of();
            editing = false;
        }
        command.setSections(buildSectionEntries(rows, existingSectionsBySlug));
        command.setContactMethods(buildContactEntries(contactTypes, existingContactsBySlug));

        model.addAttribute("command", command);
        model.addAttribute("editing", editing);
        populateReferenceData(model, rows, email);
        return FORM_VIEW;
    }

    @PostMapping("/form")
    public String submit(
            @ModelAttribute("command") SubmissionFormCommand command,
            BindingResult bindingResult,
            Authentication authentication,
            Model model) {
        String email = (String) authentication.getPrincipal();
        boolean editing = submissionService.determineMode(email).mode() == SubmissionMode.EDIT;

        if (bindingResult.hasErrors()) {
            String message = "Please check the highlighted fields and try again.";
            return rerenderFormWithError(command, editing, email, model, message);
        }

        try {
            if (editing) {
                submissionService.editFromForm(email, command);
            } else {
                submissionService.createFromForm(email, command);
            }
        } catch (SubmissionValidationException | TestimonialAlreadyExistsException ex) {
            return rerenderFormWithError(command, editing, email, model, ex.getMessage());
        }
        return REDIRECT_TO_CONFIRMATION;
    }

    // -- confirmation --

    @GetMapping("/confirmation")
    public String confirmation(Authentication authentication, Model model) {
        model.addAttribute("email", authentication.getPrincipal());
        return CONFIRMATION_VIEW;
    }

    // -- helpers --

    private String rerenderFormWithError(
            SubmissionFormCommand command, boolean editing, String email, Model model, String error) {
        List<TopicCatalogEntryDto> topicCatalog = submissionService.listTopicCatalog();
        List<TopicFormRow> rows = buildTopicFormRows(topicCatalog);
        model.addAttribute("command", command);
        model.addAttribute("editing", editing);
        model.addAttribute("error", error);
        populateReferenceData(model, rows, email);
        return FORM_VIEW;
    }

    private void populateReferenceData(Model model, List<TopicFormRow> rows, String email) {
        model.addAttribute("email", email);
        model.addAttribute("topicFormRows", rows);
        model.addAttribute("countries", submissionService.listAllCountries());
        model.addAttribute("contactTypes", submissionService.listActiveContactTypes());
        model.addAttribute("achievements", submissionService.listActiveAchievements());
        model.addAttribute("newPhotoSlots", SubmissionService.NEW_PHOTO_SLOTS_PER_SECTION);
    }

    private void prefillCommand(SubmissionFormCommand command, TestimonialSubmissionView existing) {
        command.setFirstName(existing.firstName());
        command.setLastName(existing.lastName());
        command.setRollNumber(existing.rollNumber());
        command.setAdmissionYear(existing.admissionYear());
        command.setCountryCode(existing.countryCode());
        command.setRecommendationScore(existing.recommendationScore());
        command.setAchievementSlugs(new ArrayList<>(existing.achievementSlugs()));
        command.setDataProcessingConsent(existing.dataProcessingConsent());
    }

    /**
     * One contact-method row per active contact type (decision 5's catalog
     * is small enough that this covers the realistic case) so the form never
     * needs a JS-driven "add another contact method" control — an unused row
     * is simply left blank and dropped by {@link
     * SubmissionService#createFromForm}/{@link SubmissionService#editFromForm}.
     */
    private List<ContactFormEntry> buildContactEntries(
            List<ContactTypeView> contactTypes,
            Map<String, TestimonialSubmissionView.ContactMethodView> existingByTypeSlug) {
        List<ContactFormEntry> entries = new ArrayList<>();
        for (ContactTypeView contactType : contactTypes) {
            ContactFormEntry entry = new ContactFormEntry();
            entry.setTypeSlug(contactType.slug());
            TestimonialSubmissionView.ContactMethodView existing = existingByTypeSlug.get(contactType.slug());
            if (existing != null) {
                entry.setValue(existing.value());
                entry.setPublicContact(existing.isPublic());
            }
            entries.add(entry);
        }
        return entries;
    }

    private List<TopicFormRow> buildTopicFormRows(List<TopicCatalogEntryDto> topicCatalog) {
        List<TopicFormRow> rows = new ArrayList<>();
        for (TopicCatalogEntryDto entry : topicCatalog) {
            if ("GROUP".equals(entry.kind())) {
                boolean first = true;
                for (TopicPickDto pick : entry.subtopics()) {
                    rows.add(new TopicFormRow(entry.label(), first, pick.slug(), pick.label(), pick.guidingPrompt()));
                    first = false;
                }
            } else {
                rows.add(new TopicFormRow(null, false, entry.slug(), entry.label(), entry.guidingPrompt()));
            }
        }
        return rows;
    }

    private List<SectionFormEntry> buildSectionEntries(
            List<TopicFormRow> rows, Map<String, TestimonialSubmissionView.SectionView> existingBySlug) {
        List<SectionFormEntry> entries = new ArrayList<>();
        for (TopicFormRow row : rows) {
            SectionFormEntry entry = new SectionFormEntry();
            entry.setTopicSlug(row.topicSlug());
            TestimonialSubmissionView.SectionView existing = existingBySlug.get(row.topicSlug());
            if (existing != null) {
                entry.setAnswerText(existing.answer());
                List<String> urls = new ArrayList<>();
                List<String> tags = new ArrayList<>();
                for (TestimonialSubmissionView.PhotoRef photo : existing.photos()) {
                    urls.add(photo.url());
                    tags.add(String.join(", ", photo.tags()));
                }
                entry.setExistingPhotoUrls(urls);
                entry.setExistingPhotoTags(tags);
            }
            entries.add(entry);
        }
        return entries;
    }

    /**
     * One flattened row for the form template to iterate — a topic-catalog
     * traversal (groups with their subtopics, then standalone topics) pre-
     * flattened here rather than re-derived in Thymeleaf, so {@code
     * command.sections[i]} and {@code topicFormRows[i]} are guaranteed to
     * line up index-for-index without the template having to track a running
     * counter across nested loops itself.
     */
    private record TopicFormRow(
            String groupLabel, boolean groupStart, String topicSlug, String topicLabel, String guidingPrompt) {
    }
}
