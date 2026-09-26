package com.iitm.beacon.submission;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Flat/indexed shape bound by Spring MVC's {@code @ModelAttribute} from the
 * plain multipart {@code POST /submissions/form} HTML submission (both
 * create and edit share this one command and one template). Deliberately a
 * mutable JavaBean, not a record, since Thymeleaf's {@code th:field}
 * indexed-list binding (used for {@code sections}/{@code contactMethods})
 * needs settable properties and auto-growable lists.
 *
 * <p>{@link SubmissionService#createFromForm}/{@link
 * SubmissionService#editFromForm} translate this into the exact same {@code
 * TestimonialSubmissionRequest} + file-map shape the already-tested {@link
 * SubmissionService#create}/{@link SubmissionService#edit} expect, then
 * delegate to those methods unchanged — this class carries no validation or
 * business logic of its own.
 */
@Getter
@Setter
@NoArgsConstructor
public class SubmissionFormCommand {

    private String firstName;
    private String lastName;
    private String rollNumber;
    private Integer admissionYear;
    private String countryCode;
    private Integer recommendationScore;
    private List<SectionFormEntry> sections = new ArrayList<>();
    private List<String> achievementSlugs = new ArrayList<>();
    private List<ContactFormEntry> contactMethods = new ArrayList<>();
    private boolean dataProcessingConsent;
}
