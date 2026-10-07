package com.iitm.beacon.catalogadmin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code POST /api/catalog/achievements} body (decision 28). Strings are trimmed on construction. */
public record AchievementCreateRequest(
        @NotBlank(message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.SLUG_MAX, message = CatalogText.SLUG_TOO_LONG_MESSAGE)
        @Pattern(regexp = CatalogText.SLUG_PATTERN, message = CatalogText.SLUG_PATTERN_MESSAGE)
        String slug,
        @NotBlank(message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.LABEL_MAX, message = CatalogText.LABEL_TOO_LONG_MESSAGE)
        String label,
        @NotNull(message = CatalogText.REQUIRED_MESSAGE)
        @Min(value = 0, message = CatalogText.DISPLAY_ORDER_MESSAGE)
        @Max(value = CatalogText.DISPLAY_ORDER_MAX, message = CatalogText.DISPLAY_ORDER_MESSAGE)
        Integer displayOrder) {

    public AchievementCreateRequest {
        slug = CatalogText.trim(slug);
        label = CatalogText.trim(label);
    }
}
