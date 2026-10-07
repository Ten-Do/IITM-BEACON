package com.iitm.beacon.catalogadmin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The topic form page's fields ({@code catalogadmin/topic-form.html}), a
 * plain mutable JavaBean for {@code @ModelAttribute} binding. The form always
 * posts every field, so the rules of {@link TopicCreateRequest} apply on
 * create and on edit; the setters trim. {@code topicGroupId} {@code null}
 * (the "Standalone" option) means a standalone topic — on edit too, where it
 * becomes an explicit "make standalone" ({@code Optional.empty()}) in the
 * PATCH. Active/inactive is the list page's toggle, not a form field.
 */
@Getter
@NoArgsConstructor
public class TopicForm {

    @NotBlank(message = CatalogText.BLANK_MESSAGE)
    @Size(max = CatalogText.SLUG_MAX, message = CatalogText.SLUG_TOO_LONG_MESSAGE)
    @Pattern(regexp = CatalogText.SLUG_PATTERN, message = CatalogText.SLUG_PATTERN_MESSAGE)
    private String slug;

    @NotBlank(message = CatalogText.BLANK_MESSAGE)
    @Size(max = CatalogText.LABEL_MAX, message = CatalogText.LABEL_TOO_LONG_MESSAGE)
    private String label;

    @NotBlank(message = CatalogText.BLANK_MESSAGE)
    @Size(max = CatalogText.PROMPT_MAX, message = CatalogText.PROMPT_TOO_LONG_MESSAGE)
    private String guidingPrompt;

    @NotNull(message = CatalogText.REQUIRED_MESSAGE)
    @Min(value = 0, message = CatalogText.DISPLAY_ORDER_MESSAGE)
    @Max(value = CatalogText.DISPLAY_ORDER_MAX, message = CatalogText.DISPLAY_ORDER_MESSAGE)
    private Integer displayOrder;

    private Long topicGroupId;

    static TopicForm of(TopicDto topic) {
        TopicForm form = new TopicForm();
        form.setSlug(topic.slug());
        form.setLabel(topic.label());
        form.setGuidingPrompt(topic.guidingPrompt());
        form.setDisplayOrder(topic.displayOrder());
        form.setTopicGroupId(topic.topicGroupId());
        return form;
    }

    public void setSlug(String slug) {
        this.slug = CatalogText.trim(slug);
    }

    public void setLabel(String label) {
        this.label = CatalogText.trim(label);
    }

    public void setGuidingPrompt(String guidingPrompt) {
        this.guidingPrompt = CatalogText.trim(guidingPrompt);
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public void setTopicGroupId(Long topicGroupId) {
        this.topicGroupId = topicGroupId;
    }

    TopicCreateRequest toCreateRequest() {
        return new TopicCreateRequest(slug, label, guidingPrompt, displayOrder, topicGroupId);
    }

    /** Every field the form edits, the group included; the active flag stays as it is. */
    TopicPatchRequest toPatchRequest() {
        return new TopicPatchRequest(slug, label, guidingPrompt, displayOrder, null, Optional.ofNullable(topicGroupId));
    }
}
