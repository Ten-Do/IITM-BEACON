package com.iitm.beacon.submission;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One contact-method row bound from the submission HTML form (plain mutable
 * JavaBean, not a record — Spring's {@code @ModelAttribute}/Thymeleaf
 * {@code th:field} indexed-list binding needs settable properties). Adapted
 * to {@code TestimonialSubmissionRequest.ContactMethodInput} by {@link
 * SubmissionService#createFromForm}/{@link SubmissionService#editFromForm}.
 *
 * <p>The "is public" flag is deliberately named {@code publicContact}
 * (avoiding an {@code is}-prefixed field name) so the generated getter/setter
 * pair and the resulting JavaBean property name all agree unambiguously —
 * {@code contactMethods[i].publicContact} in the HTML form.
 */
@Getter
@Setter
@NoArgsConstructor
public class ContactFormEntry {

    private String typeSlug;
    private String value;
    private boolean publicContact;
}
