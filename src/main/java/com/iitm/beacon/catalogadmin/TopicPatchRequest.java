package com.iitm.beacon.catalogadmin;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Optional;

/**
 * {@code PATCH /api/catalog/topics/{id}} body (decision 28): only the fields
 * present change ({@code null} = leave as is). Strings are trimmed on
 * construction.
 *
 * <p>{@code topicGroupId} has three states: the JSON property absent →
 * {@code null} (leave the group as is); an explicit JSON {@code null} →
 * {@link Optional#empty()} (make the topic standalone); a number → that
 * group (re-parent). JSON is bound through {@link Builder}, not the record's
 * constructor: Jackson fills a missing constructor argument of type {@code
 * Optional} with {@code Optional.empty()}, exactly as it does an explicit
 * {@code null}, whereas a builder method for a missing property is simply
 * never called.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@JsonDeserialize(builder = TopicPatchRequest.Builder.class)
public record TopicPatchRequest(
        @Size(min = 1, message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.SLUG_MAX, message = CatalogText.SLUG_TOO_LONG_MESSAGE)
        @Pattern(regexp = CatalogText.SLUG_PATTERN, message = CatalogText.SLUG_PATTERN_MESSAGE)
        String slug,
        @Size(min = 1, message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.LABEL_MAX, message = CatalogText.LABEL_TOO_LONG_MESSAGE)
        String label,
        @Size(min = 1, message = CatalogText.BLANK_MESSAGE)
        @Size(max = CatalogText.PROMPT_MAX, message = CatalogText.PROMPT_TOO_LONG_MESSAGE)
        String guidingPrompt,
        @Min(value = 0, message = CatalogText.DISPLAY_ORDER_MESSAGE)
        @Max(value = CatalogText.DISPLAY_ORDER_MAX, message = CatalogText.DISPLAY_ORDER_MESSAGE)
        Integer displayOrder,
        Boolean active,
        Optional<Long> topicGroupId) {

    public TopicPatchRequest {
        slug = CatalogText.trim(slug);
        label = CatalogText.trim(label);
        guidingPrompt = CatalogText.trim(guidingPrompt);
    }

    /**
     * Jackson's way into the record: each method is called only for a
     * property present in the JSON, so an absent {@code topicGroupId} stays
     * {@code null}, while a JSON {@code null} arrives as {@link
     * Optional#empty()} (Jackson's null value for {@code Optional}).
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private String slug;
        private String label;
        private String guidingPrompt;
        private Integer displayOrder;
        private Boolean active;
        private Optional<Long> topicGroupId;

        public Builder slug(String slug) {
            this.slug = slug;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder guidingPrompt(String guidingPrompt) {
            this.guidingPrompt = guidingPrompt;
            return this;
        }

        public Builder displayOrder(Integer displayOrder) {
            this.displayOrder = displayOrder;
            return this;
        }

        public Builder active(Boolean active) {
            this.active = active;
            return this;
        }

        public Builder topicGroupId(Optional<Long> topicGroupId) {
            this.topicGroupId = topicGroupId;
            return this;
        }

        public TopicPatchRequest build() {
            return new TopicPatchRequest(slug, label, guidingPrompt, displayOrder, active, topicGroupId);
        }
    }
}
