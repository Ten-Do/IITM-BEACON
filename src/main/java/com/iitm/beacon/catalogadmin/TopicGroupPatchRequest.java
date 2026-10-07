package com.iitm.beacon.catalogadmin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/catalog/topic-groups/{id}} body (decision 28): only the
 * fields present change ({@code null} = leave as is). Strings are trimmed on
 * construction, so a whitespace-only label is blank.
 */
public record TopicGroupPatchRequest(
        @Size(min = 1, message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.LABEL_MAX, message = CatalogText.LABEL_TOO_LONG_MESSAGE)
        String label,
        @Min(value = 0, message = CatalogText.DISPLAY_ORDER_MESSAGE)
        @Max(value = CatalogText.DISPLAY_ORDER_MAX, message = CatalogText.DISPLAY_ORDER_MESSAGE)
        Integer displayOrder,
        Boolean active) {

    public TopicGroupPatchRequest {
        label = CatalogText.trim(label);
    }
}
