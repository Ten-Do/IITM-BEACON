package com.iitm.beacon.catalogadmin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The topic-group form page's fields ({@code catalogadmin/topic-group-form.html}),
 * a plain mutable JavaBean for {@code @ModelAttribute} binding. The form
 * always posts every field, so the same rules as {@link
 * TopicGroupCreateRequest} apply on create and on edit; the setters trim,
 * so the rules see the trimmed value. Active/inactive is the list page's
 * toggle, not a form field.
 */
@Getter
@NoArgsConstructor
public class TopicGroupForm {

    @NotBlank(message = CatalogText.BLANK_MESSAGE)
    @Size(max = CatalogText.LABEL_MAX, message = CatalogText.LABEL_TOO_LONG_MESSAGE)
    private String label;

    @NotNull(message = CatalogText.REQUIRED_MESSAGE)
    @Min(value = 0, message = CatalogText.DISPLAY_ORDER_MESSAGE)
    @Max(value = CatalogText.DISPLAY_ORDER_MAX, message = CatalogText.DISPLAY_ORDER_MESSAGE)
    private Integer displayOrder;

    static TopicGroupForm of(TopicGroupDto group) {
        TopicGroupForm form = new TopicGroupForm();
        form.setLabel(group.label());
        form.setDisplayOrder(group.displayOrder());
        return form;
    }

    public void setLabel(String label) {
        this.label = CatalogText.trim(label);
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    TopicGroupCreateRequest toCreateRequest() {
        return new TopicGroupCreateRequest(label, displayOrder);
    }

    /** Every field the form edits; the active flag stays as it is. */
    TopicGroupPatchRequest toPatchRequest() {
        return new TopicGroupPatchRequest(label, displayOrder, null);
    }
}
