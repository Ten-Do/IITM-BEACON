package com.iitm.beacon.catalogadmin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The achievement form page's fields ({@code catalogadmin/achievement-form.html}),
 * a plain mutable JavaBean for {@code @ModelAttribute} binding. The form
 * always posts every field, so the rules of {@link AchievementCreateRequest}
 * apply on create and on edit; the setters trim. Active/inactive is the list
 * page's toggle, not a form field.
 */
@Getter
@NoArgsConstructor
public class AchievementForm {

    @NotBlank(message = CatalogText.BLANK_MESSAGE)
    @Size(max = CatalogText.SLUG_MAX, message = CatalogText.SLUG_TOO_LONG_MESSAGE)
    @Pattern(regexp = CatalogText.SLUG_PATTERN, message = CatalogText.SLUG_PATTERN_MESSAGE)
    private String slug;

    @NotBlank(message = CatalogText.BLANK_MESSAGE)
    @Size(max = CatalogText.LABEL_MAX, message = CatalogText.LABEL_TOO_LONG_MESSAGE)
    private String label;

    @NotNull(message = CatalogText.REQUIRED_MESSAGE)
    @Min(value = 0, message = CatalogText.DISPLAY_ORDER_MESSAGE)
    @Max(value = CatalogText.DISPLAY_ORDER_MAX, message = CatalogText.DISPLAY_ORDER_MESSAGE)
    private Integer displayOrder;

    static AchievementForm of(AchievementDto achievement) {
        AchievementForm form = new AchievementForm();
        form.setSlug(achievement.slug());
        form.setLabel(achievement.label());
        form.setDisplayOrder(achievement.displayOrder());
        return form;
    }

    public void setSlug(String slug) {
        this.slug = CatalogText.trim(slug);
    }

    public void setLabel(String label) {
        this.label = CatalogText.trim(label);
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    AchievementCreateRequest toCreateRequest() {
        return new AchievementCreateRequest(slug, label, displayOrder);
    }

    /** Every field the form edits; the active flag stays as it is. */
    AchievementPatchRequest toPatchRequest() {
        return new AchievementPatchRequest(slug, label, displayOrder, null);
    }
}
