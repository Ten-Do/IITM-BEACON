package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CatalogValidationException} (decision 28): every broken catalog
 * rule as a {@link FieldViolation}, joined into the message the way {@link
 * SubmissionValidationException} joins them.
 */
class CatalogValidationExceptionTest {

    @Test
    void joinsFieldViolationsAsFieldColonMessageInOrder() {
        CatalogValidationException ex = new CatalogValidationException(List.of(
                new FieldViolation("topicGroupId", "names no existing topic group"),
                new FieldViolation("label", "must not be blank")));

        assertThat(ex.getMessage())
                .isEqualTo("topicGroupId: names no existing topic group, label: must not be blank");
        assertThat(ex.getViolations()).extracting(FieldViolation::field).containsExactly("topicGroupId", "label");
    }
}
