package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.submission.TestimonialSubmissionRequest.ContactMethodInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The request record's own normalization and Bean Validation (decision 10):
 * the roll number is trimmed and uppercased before its {@code AA00A000}
 * pattern is checked, the admission year has a 1959 floor (its
 * current-year ceiling needs the application clock and lives in {@link
 * SubmissionService} — see {@code SubmissionServiceAdmissionYearTest}), and
 * a contact value is trimmed (decision 5).
 */
class TestimonialSubmissionRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private static final String ROLL_NUMBER_FORMAT_MESSAGE =
            "must look like CS21B001 (two letters, two digits, a letter, three digits)";

    private static TestimonialSubmissionRequest withRollNumber(String rollNumber) {
        return new TestimonialSubmissionRequest("David", "Jones", rollNumber, 2024, "IN", 8,
                List.of(new SectionInput("general", "Text.", List.of())), List.of(), List.of(), true);
    }

    private static TestimonialSubmissionRequest withAdmissionYear(Integer admissionYear) {
        return new TestimonialSubmissionRequest("David", "Jones", "CS21B001", admissionYear, "IN", 8,
                List.of(new SectionInput("general", "Text.", List.of())), List.of(), List.of(), true);
    }

    private static List<String> messagesFor(TestimonialSubmissionRequest request, String field) {
        Set<ConstraintViolation<TestimonialSubmissionRequest>> violations = validator.validate(request);
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    // -- roll number --

    @Test
    void rollNumber_validShape_passes() {
        assertThat(messagesFor(withRollNumber("CS21B001"), "rollNumber")).isEmpty();
    }

    @Test
    void rollNumber_lowercase_isUppercasedAndPasses() {
        TestimonialSubmissionRequest request = withRollNumber("cs21b001");

        assertThat(request.rollNumber()).isEqualTo("CS21B001");
        assertThat(messagesFor(request, "rollNumber")).isEmpty();
    }

    @Test
    void rollNumber_surroundingWhitespace_isTrimmedAndPasses() {
        TestimonialSubmissionRequest request = withRollNumber(" \tcs21B001  ");

        assertThat(request.rollNumber()).isEqualTo("CS21B001");
        assertThat(messagesFor(request, "rollNumber")).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = {
        "CS21B00", // 7 characters
        "CS21B0012", // 9 characters
        "C521B001", // digit where a letter is expected
        "CS2XB001", // letter where a digit is expected
        "CS211001", // digit where the middle letter is expected
        "CS21BB01", // letter among the last three digits
        "СS21B001", // Cyrillic "С", not a Latin letter
        "CS21B０01", // full-width digit
        "CS 21B001", // inner space
        "CS21-B001"
    })
    void rollNumber_wrongShape_isRejectedWithAReadableFormatMessage(String rollNumber) {
        assertThat(messagesFor(withRollNumber(rollNumber), "rollNumber")).containsExactly(ROLL_NUMBER_FORMAT_MESSAGE);
    }

    @ParameterizedTest(name = "\"{0}\" only reports that it's blank")
    @ValueSource(strings = {"", "   "})
    void rollNumber_blank_reportsOnlyThatItIsBlankNotAlsoTheFormat(String rollNumber) {
        // Hibernate Validator's own @NotBlank message (locale-dependent), and nothing else.
        assertThat(messagesFor(withRollNumber(rollNumber), "rollNumber"))
                .hasSize(1)
                .doesNotContain(ROLL_NUMBER_FORMAT_MESSAGE);
    }

    @Test
    void rollNumber_null_staysNullAndIsReportedAsBlank() {
        TestimonialSubmissionRequest request = withRollNumber(null);

        assertThat(request.rollNumber()).isNull();
        assertThat(messagesFor(request, "rollNumber")).hasSize(1).doesNotContain(ROLL_NUMBER_FORMAT_MESSAGE);
    }

    // -- admission year (floor only; the ceiling is clock-based, in the service) --

    @Test
    void admissionYear_1958_isBelowTheFloor() {
        assertThat(messagesFor(withAdmissionYear(1958), "admissionYear"))
                .containsExactly("must be between 1959 and the current year");
    }

    @Test
    void admissionYear_1959_theFoundingYear_passes() {
        assertThat(messagesFor(withAdmissionYear(1959), "admissionYear")).isEmpty();
    }

    @Test
    void admissionYear_negative_isBelowTheFloor() {
        assertThat(messagesFor(withAdmissionYear(-2024), "admissionYear"))
                .containsExactly("must be between 1959 and the current year");
    }

    @Test
    void admissionYear_null_isRequired() {
        assertThat(messagesFor(withAdmissionYear(null), "admissionYear"))
                .hasSize(1)
                .doesNotContain("must be between 1959 and the current year");
    }

    // -- country code --

    private static final String SELECT_COUNTRY_MESSAGE = "Please select your country.";
    private static final String COUNTRY_CODE_LENGTH_MESSAGE = "must be a two-letter country code";

    private static TestimonialSubmissionRequest withCountryCode(String countryCode) {
        return new TestimonialSubmissionRequest("David", "Jones", "CS21B001", 2024, countryCode, 8,
                List.of(new SectionInput("general", "Text.", List.of())), List.of(), List.of(), true);
    }

    @Test
    void countryCode_twoLetters_passes() {
        // Whether it's a real country is the service's reference check, not Bean Validation's.
        assertThat(messagesFor(withCountryCode("IN"), "countryCode")).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" only asks to select a country")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  ", "   ", "\t"})
    void countryCode_blank_reportsExactlyOneReadableMessage(String countryCode) {
        // "" and "   " also break the two-letter length rule; that must not
        // be reported on top of the missing country.
        assertThat(messagesFor(withCountryCode(countryCode), "countryCode")).containsExactly(SELECT_COUNTRY_MESSAGE);
    }

    @ParameterizedTest(name = "\"{0}\" is not a two-letter code")
    @ValueSource(strings = {"I", "IND", "INDIA"})
    void countryCode_wrongLength_reportsOnlyTheLengthInEnglish(String countryCode) {
        assertThat(messagesFor(withCountryCode(countryCode), "countryCode"))
                .containsExactly(COUNTRY_CODE_LENGTH_MESSAGE);
    }

    // -- sections --

    @Test
    void sections_empty_reportsTheSameMessageAsAllBlankSections() {
        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest(
                "David", "Jones", "CS21B001", 2024, "IN", 8, List.of(), List.of(), List.of(), true);

        assertThat(messagesFor(request, "sections")).containsExactly("At least one section must be filled in.");
    }

    // -- contact value --

    @Test
    void contactValue_surroundingWhitespace_isTrimmed() {
        assertThat(new ContactMethodInput("email", "  david@example.com \t", true).value())
                .isEqualTo("david@example.com");
    }

    @Test
    void contactValue_null_staysNull() {
        assertThat(new ContactMethodInput("email", null, false).value()).isNull();
    }

    @Test
    void contactValue_whitespaceOnly_isReportedAsBlank() {
        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest(
                "David", "Jones", "CS21B001", 2024, "IN", 8,
                List.of(new SectionInput("general", "Text.", List.of())), List.of(),
                List.of(new ContactMethodInput("email", "   ", false)), true);

        assertThat(messagesFor(request, "contactMethods[0].value")).hasSize(1);
    }
}
