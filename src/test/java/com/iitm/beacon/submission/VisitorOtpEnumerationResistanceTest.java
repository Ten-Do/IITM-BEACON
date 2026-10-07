package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.testsupport.Csrf;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * NFR-VISITOR-OTP-BRUTEFORCE, enumeration: two OTP requests — one for an
 * email that has a testimonial (of any status), one for an email that has
 * none — must be indistinguishable from the response alone (UC-VISITOR-LOGIN
 * alternate flow). Compared on the page flow ({@code POST /submissions/login})
 * and the REST endpoint ({@code POST /api/submissions/otp/request}), both
 * for an accepted request and for a rate-limited one: status, every header
 * exactly — the {@code Location} too, which no longer carries the email (the
 * code step finds it in the session) — except the {@code Date} and the
 * cookie values, which differ per request anyway; the body exactly; and the
 * number of codes mailed. Neither response contains the email anywhere.
 *
 * <p>Own context: a per-email request limit of 1, so a second request
 * answers 429, and a frozen {@link MutableClock}, so even the error body's
 * timestamp can be compared byte for byte. Every invocation uses its own
 * pair of emails, since the limiter outlives a single test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "visitor.otp.request-limit-per-email=1",
            "visitor.otp.request-window-per-email=PT1M",
            "visitor.otp.request-limit-per-ip=1000"
        })
@Import(VisitorOtpEnumerationResistanceTest.FrozenClockTestConfig.class)
class VisitorOtpEnumerationResistanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @MockitoBean
    private OtpMailer otpMailer;

    /** Same length as its partner from {@link #withoutTestimonial}, so not even a length could differ. */
    private String withTestimonial(String flow, TestimonialStatus status) {
        String email = flow + "-has-" + status.name().toLowerCase() + "@example.com";
        Testimonial testimonial = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(status)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        testimonialRepository.saveAndFlush(testimonial);
        return email;
    }

    private static String withoutTestimonial(String flow, TestimonialStatus status) {
        return flow + "-not-" + status.name().toLowerCase() + "@example.com";
    }

    private MockHttpServletResponse pageRequest(String email) throws Exception {
        return mockMvc.perform(post("/submissions/login").param("email", email).with(Csrf.csrfField()))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse restRequest(String email) throws Exception {
        return mockMvc.perform(post("/api/submissions/otp/request")
                        .with(Csrf.csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(email))))
                .andReturn()
                .getResponse();
    }

    /**
     * Every header as name → values, with what may legitimately differ
     * between any two requests blanked: the {@code Date} value and each
     * cookie's value (a session id or token). Nothing else — the email is
     * echoed nowhere.
     */
    private static Map<String, List<String>> comparableHeaders(MockHttpServletResponse response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : response.getHeaderNames()) {
            List<String> values = new ArrayList<>();
            for (String value : response.getHeaders(name)) {
                if ("Date".equalsIgnoreCase(name)) {
                    values.add("{date}");
                } else if ("Set-Cookie".equalsIgnoreCase(name)) {
                    values.add(value.replaceFirst("^([^=]+)=[^;]*", "$1={value}"));
                } else {
                    values.add(value);
                }
            }
            headers.put(name, values);
        }
        return headers;
    }

    private static void assertIndistinguishable(
            MockHttpServletResponse withTestimonial, String emailWith,
            MockHttpServletResponse without, String emailWithout) throws Exception {
        assertThat(withTestimonial.getStatus()).isEqualTo(without.getStatus());
        assertThat(comparableHeaders(withTestimonial)).isEqualTo(comparableHeaders(without));
        assertThat(withTestimonial.getContentAsString()).isEqualTo(without.getContentAsString());
        assertEchoesNothingOf(withTestimonial, emailWith);
        assertEchoesNothingOf(without, emailWithout);
    }

    private static void assertEchoesNothingOf(MockHttpServletResponse response, String email) throws Exception {
        String localPart = email.substring(0, email.indexOf('@'));
        for (String name : response.getHeaderNames()) {
            assertThat(response.getHeaders(name)).as(name).noneMatch(value -> value.contains(localPart));
        }
        assertThat(response.getContentAsString()).doesNotContain(localPart);
    }

    private void assertOneCodeMailedToEach(String first, String second) {
        verify(otpMailer, times(1)).sendOtp(eq(first), anyString());
        verify(otpMailer, times(1)).sendOtp(eq(second), anyString());
    }

    @ParameterizedTest
    @EnumSource(TestimonialStatus.class)
    void pageFlow_requestForAnEmailWithATestimonial_looksExactlyLikeOneWithout(TestimonialStatus status)
            throws Exception {
        String emailWith = withTestimonial("page", status);
        String emailWithout = withoutTestimonial("page", status);

        MockHttpServletResponse with = pageRequest(emailWith);
        MockHttpServletResponse without = pageRequest(emailWithout);

        assertThat(with.getStatus()).isEqualTo(302);
        assertThat(with.getRedirectedUrl()).isEqualTo("/submissions/login/code");
        assertThat(without.getRedirectedUrl()).isEqualTo("/submissions/login/code");
        assertIndistinguishable(with, emailWith, without, emailWithout);
        assertOneCodeMailedToEach(emailWith, emailWithout);
    }

    @ParameterizedTest
    @EnumSource(TestimonialStatus.class)
    void rest_requestForAnEmailWithATestimonial_looksExactlyLikeOneWithout(TestimonialStatus status)
            throws Exception {
        String emailWith = withTestimonial("rest", status);
        String emailWithout = withoutTestimonial("rest", status);

        MockHttpServletResponse with = restRequest(emailWith);
        MockHttpServletResponse without = restRequest(emailWithout);

        assertThat(with.getStatus()).isEqualTo(202);
        assertIndistinguishable(with, emailWith, without, emailWithout);
        assertOneCodeMailedToEach(emailWith, emailWithout);
    }

    @ParameterizedTest
    @EnumSource(TestimonialStatus.class)
    void rest_rateLimitedRequestForAnEmailWithATestimonial_looksExactlyLikeOneWithout(TestimonialStatus status)
            throws Exception {
        String emailWith = withTestimonial("rest429", status);
        String emailWithout = withoutTestimonial("rest429", status);
        restRequest(emailWith);
        restRequest(emailWithout);

        MockHttpServletResponse with = restRequest(emailWith);
        MockHttpServletResponse without = restRequest(emailWithout);

        assertThat(with.getStatus()).isEqualTo(429);
        assertIndistinguishable(with, emailWith, without, emailWithout);
        assertOneCodeMailedToEach(emailWith, emailWithout);
    }

    @ParameterizedTest
    @EnumSource(TestimonialStatus.class)
    void pageFlow_rateLimitedRequestForAnEmailWithATestimonial_looksExactlyLikeOneWithout(TestimonialStatus status)
            throws Exception {
        String emailWith = withTestimonial("page429", status);
        String emailWithout = withoutTestimonial("page429", status);
        pageRequest(emailWith);
        pageRequest(emailWithout);

        MockHttpServletResponse with = pageRequest(emailWith);
        MockHttpServletResponse without = pageRequest(emailWithout);

        assertThat(with.getStatus()).isEqualTo(429);
        assertIndistinguishable(with, emailWith, without, emailWithout);
        assertOneCodeMailedToEach(emailWith, emailWithout);
    }

    @TestConfiguration
    static class FrozenClockTestConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }
}
