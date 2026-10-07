package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Bean Validation and trimming of the catalog's REST request records
 * (decision 28): {@code slug} {@code ^[a-z0-9_]+$}, 1–64 characters;
 * {@code label} not blank, at most 120; {@code guidingPrompt} not blank, at
 * most 500; {@code displayOrder} 0–9999, required on create. Strings are
 * trimmed before they are checked, so surrounding spaces never count
 * towards a length and a whitespace-only value is blank. On a PATCH every
 * field is optional ({@code null} = leave as is), but a present one obeys
 * the same rules.
 */
class CatalogRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static Set<String> invalidFields(Object request) {
        return VALIDATOR.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static Set<String> messagesFor(Object request, String field) {
        return VALIDATOR.validate(request).stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    // -- trimming --

    @Test
    void createTopic_trimsEveryString() {
        TopicCreateRequest request = new TopicCreateRequest("  career_x \t", "  Career  ", "\n Why? ", 3, null);

        assertThat(request.slug()).isEqualTo("career_x");
        assertThat(request.label()).isEqualTo("Career");
        assertThat(request.guidingPrompt()).isEqualTo("Why?");
    }

    @Test
    void patchRequests_keepAbsentStringsNull() {
        TopicPatchRequest topic = new TopicPatchRequest(null, null, null, null, null, null);
        AchievementPatchRequest achievement = new AchievementPatchRequest(null, null, null, null);
        TopicGroupPatchRequest group = new TopicGroupPatchRequest(null, null, null);

        assertThat(topic.slug()).isNull();
        assertThat(topic.label()).isNull();
        assertThat(topic.guidingPrompt()).isNull();
        assertThat(achievement.slug()).isNull();
        assertThat(group.label()).isNull();
        assertThat(invalidFields(topic)).isEmpty();
        assertThat(invalidFields(achievement)).isEmpty();
        assertThat(invalidFields(group)).isEmpty();
    }

    // -- label: not blank, at most 120 (after trimming) --

    @Test
    void label_of120Characters_isAccepted_evenWithSurroundingSpaces() {
        String label = "a".repeat(120);

        assertThat(invalidFields(new TopicGroupCreateRequest(label, 0))).isEmpty();
        assertThat(invalidFields(new TopicGroupCreateRequest("   " + label + "  ", 0))).isEmpty();
        assertThat(invalidFields(new AchievementPatchRequest(null, " " + label + " ", null, null))).isEmpty();
    }

    @Test
    void label_of121Characters_isRejected() {
        String label = "a".repeat(121);

        assertThat(messagesFor(new TopicGroupCreateRequest(label, 0), "label"))
                .containsExactly("must be at most 120 characters");
        assertThat(invalidFields(new TopicGroupPatchRequest(label, null, null))).containsExactly("label");
        assertThat(invalidFields(new TopicCreateRequest("s", label, "p", 0, null))).containsExactly("label");
        assertThat(invalidFields(new TopicPatchRequest(null, label, null, null, null, null)))
                .containsExactly("label");
        assertThat(invalidFields(new AchievementCreateRequest("s", label, 0))).containsExactly("label");
        assertThat(invalidFields(new AchievementPatchRequest(null, label, null, null))).containsExactly("label");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n \t "})
    void blankLabel_isRejectedOnCreateAndOnPatch(String blank) {
        assertThat(messagesFor(new TopicGroupCreateRequest(blank, 0), "label")).containsExactly("must not be blank");
        assertThat(messagesFor(new TopicGroupPatchRequest(blank, null, null), "label"))
                .containsExactly("must not be blank");
        assertThat(invalidFields(new TopicCreateRequest("s", blank, "p", 0, null))).containsExactly("label");
        assertThat(invalidFields(new TopicPatchRequest(null, blank, null, null, null, null)))
                .containsExactly("label");
        assertThat(invalidFields(new AchievementCreateRequest("s", blank, 0))).containsExactly("label");
        assertThat(invalidFields(new AchievementPatchRequest(null, blank, null, null))).containsExactly("label");
    }

    @Test
    void missingLabel_isRejectedOnCreate() {
        assertThat(messagesFor(new TopicGroupCreateRequest(null, 0), "label")).containsExactly("must not be blank");
        assertThat(invalidFields(new TopicCreateRequest("s", null, "p", 0, null))).containsExactly("label");
        assertThat(invalidFields(new AchievementCreateRequest("s", null, 0))).containsExactly("label");
    }

    // -- guidingPrompt: not blank, at most 500 --

    @Test
    void guidingPrompt_of500Characters_isAccepted_evenWithSurroundingSpaces() {
        String prompt = "p".repeat(500);

        assertThat(invalidFields(new TopicCreateRequest("s", "L", "  " + prompt + " ", 0, null))).isEmpty();
        assertThat(invalidFields(new TopicPatchRequest(null, null, prompt, null, null, null))).isEmpty();
    }

    @Test
    void guidingPrompt_of501Characters_isRejected() {
        String prompt = "p".repeat(501);

        assertThat(messagesFor(new TopicCreateRequest("s", "L", prompt, 0, null), "guidingPrompt"))
                .containsExactly("must be at most 500 characters");
        assertThat(invalidFields(new TopicPatchRequest(null, null, prompt, null, null, null)))
                .containsExactly("guidingPrompt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t\n"})
    void blankGuidingPrompt_isRejected(String blank) {
        assertThat(messagesFor(new TopicCreateRequest("s", "L", blank, 0, null), "guidingPrompt"))
                .containsExactly("must not be blank");
        assertThat(invalidFields(new TopicPatchRequest(null, null, blank, null, null, null)))
                .containsExactly("guidingPrompt");
    }

    @Test
    void missingGuidingPrompt_isRejectedOnCreate() {
        assertThat(invalidFields(new TopicCreateRequest("s", "L", null, 0, null))).containsExactly("guidingPrompt");
    }

    // -- slug: ^[a-z0-9_]+$, 1-64 --

    @Test
    void slug_of64Characters_isAccepted_andOf65IsRejected() {
        String slug64 = "a".repeat(64);
        String slug65 = "a".repeat(65);

        assertThat(invalidFields(new TopicCreateRequest(slug64, "L", "P", 0, null))).isEmpty();
        assertThat(invalidFields(new AchievementCreateRequest(" " + slug64 + " ", "L", 0))).isEmpty();
        assertThat(messagesFor(new TopicCreateRequest(slug65, "L", "P", 0, null), "slug"))
                .containsExactly("must be at most 64 characters");
        assertThat(invalidFields(new TopicPatchRequest(slug65, null, null, null, null, null)))
                .containsExactly("slug");
        assertThat(invalidFields(new AchievementCreateRequest(slug65, "L", 0))).containsExactly("slug");
        assertThat(invalidFields(new AchievementPatchRequest(slug65, null, null, null))).containsExactly("slug");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "_", "abc_123", "new_friendships", "9lives"})
    void slug_ofLowercaseLettersDigitsAndUnderscores_isAccepted(String slug) {
        assertThat(invalidFields(new TopicCreateRequest(slug, "L", "P", 0, null))).isEmpty();
        assertThat(invalidFields(new AchievementPatchRequest(slug, null, null, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Career", "CAREER", "new-friends", "new friends", "a.b", "a/b", "café", "ａｂｃ", "a$",
        "<script>"})
    void slug_withAnyOtherCharacter_isRejectedWithOneMessage(String slug) {
        assertThat(messagesFor(new TopicCreateRequest(slug, "L", "P", 0, null), "slug"))
                .containsExactly("may contain only lowercase letters a-z, digits and underscores");
        assertThat(invalidFields(new TopicPatchRequest(slug, null, null, null, null, null))).containsExactly("slug");
        assertThat(invalidFields(new AchievementCreateRequest(slug, "L", 0))).containsExactly("slug");
        assertThat(invalidFields(new AchievementPatchRequest(slug, null, null, null))).containsExactly("slug");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void blankSlug_isRejectedWithOneMessage(String blank) {
        assertThat(messagesFor(new TopicCreateRequest(blank, "L", "P", 0, null), "slug"))
                .containsExactly("must not be blank");
        assertThat(messagesFor(new TopicPatchRequest(blank, null, null, null, null, null), "slug"))
                .containsExactly("must not be blank");
        assertThat(messagesFor(new AchievementCreateRequest(blank, "L", 0), "slug"))
                .containsExactly("must not be blank");
        assertThat(messagesFor(new AchievementPatchRequest(blank, null, null, null), "slug"))
                .containsExactly("must not be blank");
    }

    @Test
    void missingSlug_isRejectedOnCreate() {
        assertThat(invalidFields(new TopicCreateRequest(null, "L", "P", 0, null))).containsExactly("slug");
        assertThat(invalidFields(new AchievementCreateRequest(null, "L", 0))).containsExactly("slug");
    }

    // -- displayOrder: 0-9999, required on create --

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 9998, 9999})
    void displayOrder_withinRange_isAccepted(int order) {
        assertThat(invalidFields(new TopicGroupCreateRequest("L", order))).isEmpty();
        assertThat(invalidFields(new TopicGroupPatchRequest(null, order, null))).isEmpty();
        assertThat(invalidFields(new TopicCreateRequest("s", "L", "P", order, null))).isEmpty();
        assertThat(invalidFields(new TopicPatchRequest(null, null, null, order, null, null))).isEmpty();
        assertThat(invalidFields(new AchievementCreateRequest("s", "L", order))).isEmpty();
        assertThat(invalidFields(new AchievementPatchRequest(null, null, order, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 10000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void displayOrder_outsideRange_isRejected(int order) {
        assertThat(messagesFor(new TopicGroupCreateRequest("L", order), "displayOrder"))
                .containsExactly("must be between 0 and 9999");
        assertThat(invalidFields(new TopicGroupPatchRequest(null, order, null))).containsExactly("displayOrder");
        assertThat(invalidFields(new TopicCreateRequest("s", "L", "P", order, null))).containsExactly("displayOrder");
        assertThat(invalidFields(new TopicPatchRequest(null, null, null, order, null, null)))
                .containsExactly("displayOrder");
        assertThat(invalidFields(new AchievementCreateRequest("s", "L", order))).containsExactly("displayOrder");
        assertThat(invalidFields(new AchievementPatchRequest(null, null, order, null)))
                .containsExactly("displayOrder");
    }

    @Test
    void missingDisplayOrder_isRejectedOnCreate() {
        assertThat(messagesFor(new TopicGroupCreateRequest("L", null), "displayOrder"))
                .containsExactly("is required");
        assertThat(invalidFields(new TopicCreateRequest("s", "L", "P", null, null))).containsExactly("displayOrder");
        assertThat(invalidFields(new AchievementCreateRequest("s", "L", null))).containsExactly("displayOrder");
    }

    // -- several broken rules at once --
}
