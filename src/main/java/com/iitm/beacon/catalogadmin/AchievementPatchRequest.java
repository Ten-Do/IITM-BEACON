package com.iitm.beacon.catalogadmin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/catalog/achievements/{id}} body (decision 28): only the
 * fields present change ({@code null} = leave as is). Strings are trimmed on
 * construction.
 */
public record AchievementPatchRequest(
        @Size(min = 1, message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.SLUG_MAX, message = CatalogText.SLUG_TOO_LONG_MESSAGE)
        @Pattern(regexp = CatalogText.SLUG_PATTERN, message = CatalogText.SLUG_PATTERN_MESSAGE)
        String slug,
        @Size(min = 1, message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.LABEL_MAX, message = CatalogText.LABEL_TOO_LONG_MESSAGE)
        String label,
        @Min(value = 0, message = CatalogText.DISPLAY_ORDER_MESSAGE)
        @Max(value = CatalogText.DISPLAY_ORDER_MAX, message = CatalogText.DISPLAY_ORDER_MESSAGE)
        Integer displayOrder,
        Boolean active) {

    public AchievementPatchRequest {
        slug = CatalogText.trim(slug);
        label = CatalogText.trim(label);
    }
}
