package com.iitm.beacon.catalogadmin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/catalog/topics} body (decision 28). {@code topicGroupId}
 * omitted or {@code null} makes a standalone topic; whether it names an
 * existing group is checked by the service. Strings are trimmed on
 * construction.
 */
public record TopicCreateRequest(
        @NotBlank(message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.SLUG_MAX, message = CatalogText.SLUG_TOO_LONG_MESSAGE)
        @Pattern(regexp = CatalogText.SLUG_PATTERN, message = CatalogText.SLUG_PATTERN_MESSAGE)
        String slug,
        @NotBlank(message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.LABEL_MAX, message = CatalogText.LABEL_TOO_LONG_MESSAGE)
        String label,
        @NotBlank(message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.PROMPT_MAX, message = CatalogText.PROMPT_TOO_LONG_MESSAGE)
        String guidingPrompt,
        @NotNull(message = CatalogText.REQUIRED_MESSAGE)
        @Min(value = 0, message = CatalogText.DISPLAY_ORDER_MESSAGE)
        @Max(value = CatalogText.DISPLAY_ORDER_MAX, message = CatalogText.DISPLAY_ORDER_MESSAGE)
        Integer displayOrder,
        Long topicGroupId) {

    public TopicCreateRequest {
        slug = CatalogText.trim(slug);
        label = CatalogText.trim(label);
        guidingPrompt = CatalogText.trim(guidingPrompt);
    }
}
