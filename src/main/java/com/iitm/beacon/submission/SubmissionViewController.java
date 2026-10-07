package com.iitm.beacon.submission;

import com.iitm.beacon.common.error.FieldViolation;
import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.common.error.TestimonialAlreadyExistsException;
import com.iitm.beacon.common.score.RecommendationScoreLabels;
import com.iitm.beacon.common.security.PostLoginRedirect;
import com.iitm.beacon.common.security.SessionAuthenticator;
import com.iitm.beacon.common.web.UploadFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.InvalidPropertyException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
 * letting them reach {@code GlobalExceptionHandler} (whose answer to a page
 * is the site's generic error page — a dead end for a form submission) —
 * same convention as {@code moderation.ModerationViewController}.
 *
 * <p>For the same reason, a multipart upload that breaks a servlet-container
 * limit (too large, or too many parts) is caught by {@link
 * #handleMultipartFailure} and sent back to the form with a readable error,
 * and so is a field the data binder can't bind ({@link
 * #handleUnbindableField}, BL-014).
 * The rendered form carries its CSRF token inside that same body, so a
 * browser's upload meets the failure one step earlier, in the CSRF check,
 * where {@code config.UnreadableFormUploadHandler} answers it the same way;
 * this handler still answers a client that sends the token in the {@code
 * X-XSRF-TOKEN} header.
 *
 * <p>A rejected submission is reported per field (decision 21): every
 * violation {@link SubmissionService} returns — already at form field paths —
 * is added to the form's {@link BindingResult}, so the template shows each
 * message next to its own field; the banner only summarizes, lists the
 * violations that belong to no single field, and reminds the visitor to
 * re-attach photos (a browser can't re-fill a file input).
 * That only works because {@code spring.servlet.multipart.resolve-lazily} is
 * on: the request is then parsed while the handler's arguments are bound,
 * so the failure is raised inside this controller rather than in {@code
 * DispatcherServlet} before any handler is chosen.
 *
 * <p>The email typed at the login's first step reaches the code step in the
 * HTTP session ({@link #PENDING_EMAIL}), never in a URL — so it stays out of
 * the browser history and access logs, and the redirect after the email step
 * is the same for every email (decision 17). The code page shows it from
 * there, and "Resend code" ({@link #resendOtp}) and the verify use it from
 * there too: neither form carries it, and an email posted along is ignored.
 * One browser holds one pending visitor email — the last one asked for —
 * kept apart from a pending admin login's. Without one (a fresh browser, an
 * expired session), the code step goes back to the email step. Opening the
 * email step forgets it, and so does a successful login.
 *
 * <p>A successful {@link #verifyOtp} returns the visitor to the form or
 * confirmation page they were sent to the login from (an expired session),
 * if any, via {@link PostLoginRedirect}; otherwise to the form. A form POST
 * is never saved for the return trip — a redirect can't resend it — so an
 * expired form submission lands on a fresh form.
 */
@Controller
@RequestMapping("/submissions")
public class SubmissionViewController {

    private static final Logger log = LoggerFactory.getLogger(SubmissionViewController.class);

    /** The session attribute carrying the email from the login's email step to its code step. */
    private static final String PENDING_EMAIL = "submission.pendingLoginEmail";

    private static final String LOGIN_EMAIL_VIEW = "submission/login-email";
    private static final String LOGIN_CODE_VIEW = "submission/login-code";
    private static final String FORM_VIEW = "submission/form";
    private static final String CONFIRMATION_VIEW = "submission/confirmation";
    private static final String REDIRECT_TO_LOGIN_EMAIL = "redirect:/submissions/login";
    private static final String REDIRECT_TO_LOGIN_CODE = "redirect:/submissions/login/code";
    private static final String FORM_PATH = "/submissions/form";
    private static final String REDIRECT_TO_FORM = "redirect:" + FORM_PATH;
    private static final String REDIRECT_TO_CONFIRMATION = "redirect:/submissions/confirmation";
    /** The visitor's pages a login may return to; anything else falls back to the form. */
    private static final Set<String> VISITOR_PAGES = Set.of(FORM_PATH, "/submissions/confirmation");
    private static final String WRONG_CODE_MESSAGE =
            "That code is wrong or has expired. Check your email, or request a new one.";
    private static final String BLANK_EMAIL_MESSAGE = "Please enter your email address.";
    private static final String VISITOR_AUTHORITY = "ROLE_VISITOR";
    /**
     * Where the score slider starts when there is no score yet (a new
     * testimonial, or a re-rendered form whose score couldn't be read): the
     * top of the scale. {@code submission-form.js}'s {@code scoreSlider}
     * falls back to the same value.
     */
    private static final int DEFAULT_SCORE = RecommendationScoreLabels.MAX_SCORE;
    private static final String FIX_HIGHLIGHTED_FIELDS_MESSAGE = "Please fix the highlighted fields below.";
    private static final String REATTACH_PHOTOS_MESSAGE =
            "Photos you attached were not saved — please attach them again.";
    private static final String COMMAND = "command";
    private static final String UNREADABLE_FORM_MESSAGE =
            "The form couldn't be read, so nothing was saved. Please check it and send it again.";

    private final SubmissionService submissionService;
    private final VisitorOtpService visitorOtpService;
    private final SessionAuthenticator sessionAuthenticator;
    private final PostLoginRedirect postLoginRedirect;

    public SubmissionViewController(
            SubmissionService submissionService,
            VisitorOtpService visitorOtpService,
            SessionAuthenticator sessionAuthenticator,
            PostLoginRedirect postLoginRedirect) {
        this.submissionService = submissionService;
        this.visitorOtpService = visitorOtpService;
        this.sessionAuthenticator = sessionAuthenticator;
        this.postLoginRedirect = postLoginRedirect;
    }

    // -- login: email step --

    /**
     * The header's "Write or edit your testimonial" link always points here,
     * so a visitor who is already logged in is sent straight on to the form
     * instead of through the OTP flow again. Anyone else (anonymous, or an
     * admin session, which carries no visitor identity) gets the login page.
     * Either way, a pending email is forgotten: whoever comes back here
     * starts over.
     */
    @GetMapping("/login")
    public String loginEmailForm(Authentication authentication, HttpServletRequest request) {
        forgetPendingEmail(request);
        if (isLoggedInVisitor(authentication)) {
            return REDIRECT_TO_FORM;
        }
        return LOGIN_EMAIL_VIEW;
    }

    @PostMapping("/login")
    public String requestOtp(@RequestParam(required = false) String email, HttpServletRequest request, Model model) {
        if (email == null || email.isBlank()) {
            model.addAttribute("error", BLANK_EMAIL_MESSAGE);
            return LOGIN_EMAIL_VIEW;
        }
        request.getSession().setAttribute(PENDING_EMAIL, email);
        visitorOtpService.requestOtp(email, request.getRemoteAddr());
        return REDIRECT_TO_LOGIN_CODE;
    }

    /** "Resend code": a new code for the pending email; without one, back to the email step. */
    @PostMapping("/login/resend")
    public String resendOtp(
            @SessionAttribute(name = PENDING_EMAIL, required = false) String email, HttpServletRequest request) {
        if (email == null) {
            return REDIRECT_TO_LOGIN_EMAIL;
        }
        visitorOtpService.requestOtp(email, request.getRemoteAddr());
        return REDIRECT_TO_LOGIN_CODE;
    }

    // -- login: code step --

    @GetMapping("/login/code")
    public String loginCodeForm(
            @SessionAttribute(name = PENDING_EMAIL, required = false) String email,
            Authentication authentication,
            Model model) {
        if (isLoggedInVisitor(authentication)) {
            return REDIRECT_TO_FORM;
        }
        if (email == null) {
            return REDIRECT_TO_LOGIN_EMAIL;
        }
        model.addAttribute("email", email);
        return LOGIN_CODE_VIEW;
    }

    @PostMapping("/login/code")
    public String verifyOtp(
            @SessionAttribute(name = PENDING_EMAIL, required = false) String email,
            @RequestParam String code,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        if (email == null) {
            return REDIRECT_TO_LOGIN_EMAIL;
        }
        VisitorOtpVerifyResult result = visitorOtpService.verify(email, code);
        if (result instanceof VisitorOtpVerifyResult.Rejected) {
            model.addAttribute("email", email);
            model.addAttribute("error", WRONG_CODE_MESSAGE);
            return LOGIN_CODE_VIEW;
        }
        var verified = (VisitorOtpVerifyResult.Verified) result;
        sessionAuthenticator.login(request, response, verified.email(), "VISITOR");
        forgetPendingEmail(request);
        return "redirect:" + postLoginRedirect.resolve(request, response, VISITOR_PAGES, FORM_PATH);
    }

    // -- submission form --

    @GetMapping("/form")
    public String form(Authentication authentication, Model model) {
        String email = (String) authentication.getPrincipal();
        List<TopicPickerModel.Pick> picks = TopicPickerModel.fromCatalog(submissionService.listTopicCatalog());
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
        command.setSections(buildSectionEntries(picks, existingSectionsBySlug));
        command.setContactMethods(buildContactEntries(contactTypes, existingContactsBySlug));

        model.addAttribute(COMMAND, command);
        model.addAttribute("editing", editing);
        populateReferenceData(model, picks, email, command);
        return FORM_VIEW;
    }

    @PostMapping("/form")
    public String submit(
            @ModelAttribute(COMMAND) SubmissionFormCommand command,
            BindingResult bindingResult,
            Authentication authentication,
            Model model) {
        String email = (String) authentication.getPrincipal();
        boolean editing = submissionService.determineMode(email).mode() == SubmissionMode.EDIT;

        if (bindingResult.hasErrors()) {
            // Values that couldn't even be bound (e.g. a non-numeric year)
            // are already field errors, with messages from messages.properties.
            return rerenderFormWithErrors(command, bindingResult, List.of(), editing, email, model);
        }

        try {
            if (editing) {
                submissionService.editFromForm(email, command);
            } else {
                submissionService.createFromForm(email, command);
            }
        } catch (SubmissionValidationException ex) {
            return rerenderFormWithErrors(command, bindingResult, ex.getViolations(), editing, email, model);
        } catch (TestimonialAlreadyExistsException ex) {
            return rerenderFormWithErrors(
                    command, bindingResult, List.of(FieldViolation.global(ex.getMessage())), editing, email, model);
        }
        return REDIRECT_TO_CONFIRMATION;
    }

    // -- confirmation --

    @GetMapping("/confirmation")
    public String confirmation(Authentication authentication, Model model) {
        model.addAttribute("email", authentication.getPrincipal());
        return CONFIRMATION_VIEW;
    }

    // -- errors --

    @ExceptionHandler(MultipartException.class)
    public String handleMultipartFailure(
            MultipartException ex, HttpServletRequest request, RedirectAttributes redirectAttributes) {
        log.warn("Rejected multipart submission to {}: {}", request.getRequestURI(), ex.getMessage());
        redirectAttributes.addFlashAttribute("error", UploadFailure.MESSAGE);
        return REDIRECT_TO_FORM;
    }

    /**
     * A posted field the data binder can't bind at all (BL-014): an index
     * past its auto-grow limit of 256 ({@code sections[256]}, {@code
     * contactMethods[256]}, {@code achievementSlugs[256]}, {@code
     * sections[i].photoTags[256]}), or a negative or non-numeric one. The
     * rendered form never posts one, so only a crafted request does; it
     * fails before the handler runs, so nothing is saved and there is no
     * bound form to re-render — the visitor goes back to the form with the
     * error in its banner, like an unreadable upload.
     */
    @ExceptionHandler(InvalidPropertyException.class)
    public String handleUnbindableField(
            InvalidPropertyException ex, HttpServletRequest request, RedirectAttributes redirectAttributes) {
        // Not the property's name or the exception's message: both echo the posted field name.
        log.warn("Rejected a submission form to {} with a field that can't be bound", request.getRequestURI());
        redirectAttributes.addFlashAttribute("error", UNREADABLE_FORM_MESSAGE);
        return REDIRECT_TO_FORM;
    }

    // -- helpers --

    /**
     * Re-renders the rejected form. Unpicked topic fieldsets are disabled in
     * the browser and never posted, so the bound {@code sections} list is
     * sparse; it is re-aligned onto the catalog (one entry per topic, matched
     * by slug) before rendering, or the template would hit missing indices
     * and blank topic slugs — and each violation's {@code sections[k]} path
     * is re-pointed to follow its entry.
     *
     * <p>Violations are added with {@code addError}, not {@code rejectValue}:
     * the latter resolves the property path itself, which fails on the
     * {@code List<MultipartFile>} photo inputs. The rejected value is the
     * posted one, so each input re-renders what the visitor typed.
     */
    private String rerenderFormWithErrors(
            SubmissionFormCommand command,
            BindingResult bindingResult,
            List<FieldViolation> violations,
            boolean editing,
            String email,
            Model model) {
        boolean photosPosted = anyPhotoPosted(command.getSections());
        List<TopicPickerModel.Pick> picks = TopicPickerModel.fromCatalog(submissionService.listTopicCatalog());
        List<SectionFormEntry> posted = command.getSections();
        command.setSections(TopicPickerModel.alignSections(picks, posted));

        List<String> globalMessages = new ArrayList<>();
        for (FieldViolation violation : violations) {
            Optional<String> field = violation.isGlobal()
                    ? Optional.empty()
                    : TopicPickerModel.realignSectionField(violation.field(), posted, command.getSections());
            if (field.isPresent()) {
                bindingResult.addError(new FieldError(
                        COMMAND, field.get(), rejectedValue(bindingResult, field.get()), false, null, null,
                        violation.message()));
            } else {
                globalMessages.add(violation.message());
            }
        }

        List<String> banner = new ArrayList<>();
        if (bindingResult.hasFieldErrors()) {
            banner.add(FIX_HIGHLIGHTED_FIELDS_MESSAGE);
        }
        banner.addAll(globalMessages);
        if (photosPosted) {
            banner.add(REATTACH_PHOTOS_MESSAGE);
        }

        model.addAttribute(COMMAND, command);
        model.addAttribute("editing", editing);
        if (!banner.isEmpty()) {
            model.addAttribute("error", banner.get(0));
            model.addAttribute("errorDetails", banner.subList(1, banner.size()));
        }
        populateReferenceData(model, picks, email, command);
        return FORM_VIEW;
    }

    /**
     * The value a field was posted with, for re-rendering it. None for the
     * section-level {@code photos} anchor: the file inputs can't be re-filled
     * anyway, and there is nothing to format.
     */
    private static Object rejectedValue(BindingResult bindingResult, String field) {
        return field.endsWith(".photos") ? null : bindingResult.getRawFieldValue(field);
    }

    /** True if any new photo file (not just an empty file input) was posted with the rejected form. */
    private static boolean anyPhotoPosted(List<SectionFormEntry> sections) {
        return sections != null && sections.stream()
                .filter(section -> section != null && section.getPhotos() != null)
                .flatMap(section -> section.getPhotos().stream())
                .anyMatch(file -> file != null && !file.isEmpty());
    }

    /**
     * The score the slider (and its label) shows. A re-rendered form may
     * carry no score (unparseable input) or one outside 0-10 (rejected by
     * validation); the slider shows what a browser would clamp it to, so the
     * visible label always matches the value that will be submitted next.
     */
    private static int sliderPosition(Integer score) {
        if (score == null) {
            return DEFAULT_SCORE;
        }
        return Math.clamp(score, RecommendationScoreLabels.MIN_SCORE, RecommendationScoreLabels.MAX_SCORE);
    }

    /** Never creates a session just to forget something in it. */
    private static void forgetPendingEmail(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(PENDING_EMAIL);
        }
    }

    /** Null for an anonymous request: Spring MVC resolves an anonymous principal to no {@code Authentication}. */
    private static boolean isLoggedInVisitor(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> VISITOR_AUTHORITY.equals(authority.getAuthority()));
    }

    private void populateReferenceData(
            Model model, List<TopicPickerModel.Pick> picks, String email, SubmissionFormCommand command) {
        model.addAttribute("email", email);
        model.addAttribute("scoreLabels", RecommendationScoreLabels.all());
        model.addAttribute("currentScore", sliderPosition(command.getRecommendationScore()));
        model.addAttribute("topicPicks", picks);
        model.addAttribute("initialPicked", TopicPickerModel.initiallyPicked(picks, command.getSections()));
        model.addAttribute("countries", submissionService.listAllCountries());
        model.addAttribute("contactTypes", submissionService.listActiveContactTypes());
        model.addAttribute("achievements", submissionService.listActiveAchievements());
        model.addAttribute("photoLimits", submissionService.photoUploadLimits());
        model.addAttribute("earliestAdmissionYear", TestimonialSubmissionRequest.EARLIEST_ADMISSION_YEAR);
        model.addAttribute("latestAdmissionYear", submissionService.latestAdmissionYear());
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

    /** One entry per topic in the form's section order, pre-filled from the visitor's saved sections. */
    private List<SectionFormEntry> buildSectionEntries(
            List<TopicPickerModel.Pick> picks, Map<String, TestimonialSubmissionView.SectionView> existingBySlug) {
        List<SectionFormEntry> entries = new ArrayList<>();
        for (TopicPickerModel.Pick pick : picks) {
            for (TopicPickerModel.Field field : pick.fields()) {
                SectionFormEntry entry = new SectionFormEntry();
                entry.setTopicSlug(field.slug());
                TestimonialSubmissionView.SectionView existing = existingBySlug.get(field.slug());
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
        }
        return entries;
    }
}
