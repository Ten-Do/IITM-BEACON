package com.iitm.beacon.moderation;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.SmtpNotificationMailer;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.ClientErrors;
import com.iitm.beacon.testsupport.LogCapture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.HtmlUtils;

/**
 * UC-REJECT-TESTIMONIAL when the email to the submitter can't be sent: the
 * testimonial is not rejected — it stays {@code PENDING}, its timestamps
 * untouched, in the database itself (nothing here runs in a test
 * transaction) — the REST API answers 503 with a fixed message, the page
 * goes back to the queue with that message and the reason the admin typed,
 * and nothing logged names the address. The real {@link
 * SmtpNotificationMailer} is wired over a mocked {@link JavaMailSender}
 * that fails the way an SMTP server does, its message naming the recipient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(RejectWhenEmailFailsTest.SmtpMailerConfig.class)
class RejectWhenEmailFailsTest {

    private static final String NOT_SENT_MESSAGE =
            "The email to the submitter couldn't be sent, so the testimonial was not rejected. Try again later.";
    private static final Instant REVIEWED_BEFORE = Instant.parse("2026-01-15T08:00:00Z");

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @MockitoBean
    private JavaMailSender mailSender;

    private final List<Long> committed = new ArrayList<>();

    @AfterEach
    void deleteCommittedData() {
        committed.forEach(id -> testimonialRepository.findById(id).ifPresent(testimonialRepository::delete));
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    /** Committed, not in a test transaction: what the database holds afterwards is what the test reads. */
    private Testimonial pendingTestimonial(String email) {
        Testimonial t = Testimonial.builder()
                .firstName("Jane")
                .lastName("Doe")
                .rollNumber("GE26Z777")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.parse("2000-01-01T00:00:00Z"))
                .reviewedAt(REVIEWED_BEFORE)
                .build();
        t.getSections().add(TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("Great time overall.")
                .modified(false)
                .build());
        Testimonial saved = testimonialRepository.saveAndFlush(t);
        committed.add(saved.getId());
        return saved;
    }

    private void mailServerRejects(String recipient) {
        doThrow(new MailSendException("550 5.1.1 Recipient address rejected: " + recipient))
                .when(mailSender).send(any(SimpleMailMessage.class));
    }

    private void assertStillPending(Testimonial testimonial) {
        Testimonial reloaded = testimonialRepository.findById(testimonial.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.PENDING);
        assertThat(reloaded.getRejectedAt()).isNull();
        assertThat(reloaded.getReviewedAt()).isEqualTo(REVIEWED_BEFORE);
    }

    private void assertNothingLoggedNames(String email) {
        String localPart = email.substring(0, email.indexOf('@'));
        assertThat(logs.all())
                .noneMatch(line -> line.contains(localPart))
                .noneMatch(line -> line.contains("Recipient address rejected"));
        assertThat(logs.errors()).isEmpty();
        assertThat(logs.warnings()).singleElement().satisfies(warning -> assertThat(warning)
                .contains("was not rejected")
                .contains("MailSendException"));
    }

    // -- REST --

    @Test
    void rest_emailFails_answers503WithAFixedMessage_andTheTestimonialStaysPending() throws Exception {
        String email = "jane.rest-fails@example.com";
        Testimonial saved = pendingTestimonial(email);
        mailServerRejects(email);

        MockHttpServletResponse response = mockMvc.perform(
                        post("/api/moderation/testimonials/{id}/reject", saved.getId()).with(csrfHeader())
                                .with(authentication(admin()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Please add more detail.\"}"))
                .andReturn()
                .getResponse();

        ClientErrors.assertJsonError(response, 503, NOT_SENT_MESSAGE,
                "/api/moderation/testimonials/" + saved.getId() + "/reject");
        assertThat(response.getContentAsString()).doesNotContain("jane", "550");
        assertStillPending(saved);
        assertNothingLoggedNames(email);
    }

    @Test
    void rest_emailFails_withoutAReasonBody_answers503Too() throws Exception {
        String email = "jane.rest-fails-no-body@example.com";
        Testimonial saved = pendingTestimonial(email);
        mailServerRejects(email);

        MockHttpServletResponse response = mockMvc.perform(
                        post("/api/moderation/testimonials/{id}/reject", saved.getId()).with(csrfHeader())
                                .with(authentication(admin())))
                .andReturn()
                .getResponse();

        ClientErrors.assertJsonError(response, 503, NOT_SENT_MESSAGE,
                "/api/moderation/testimonials/" + saved.getId() + "/reject");
        assertStillPending(saved);
    }

    @Test
    void rest_emailFailsOnce_aRetryThatCanSendIt_rejects() throws Exception {
        String email = "jane.rest-retry@example.com";
        Testimonial saved = pendingTestimonial(email);
        mailServerRejects(email);
        mockMvc.perform(post("/api/moderation/testimonials/{id}/reject", saved.getId()).with(csrfHeader())
                        .with(authentication(admin())))
                .andExpect(status().isServiceUnavailable());

        reset(mailSender);
        mockMvc.perform(post("/api/moderation/testimonials/{id}/reject", saved.getId()).with(csrfHeader())
                        .with(authentication(admin())))
                .andExpect(status().isNoContent());

        assertThat(testimonialRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(TestimonialStatus.REJECTED);
    }

    @Test
    void rest_emailSent_rejectsAsBefore() throws Exception {
        Testimonial saved = pendingTestimonial("jane.rest-sent@example.com");

        mockMvc.perform(post("/api/moderation/testimonials/{id}/reject", saved.getId()).with(csrfHeader())
                        .with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Please add more detail.\"}"))
                .andExpect(status().isNoContent());

        verify(mailSender).send(any(SimpleMailMessage.class));
        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
        assertThat(reloaded.getRejectedAt()).isNotNull();
    }

    // -- page --

    @Test
    void page_emailFails_redirectsToTheQueueWithTheMessage_andTheTestimonialStaysPending() throws Exception {
        String email = "jane.page-fails@example.com";
        Testimonial saved = pendingTestimonial(email);
        mailServerRejects(email);

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()).with(csrfField())
                        .param("reason", "Please add more detail.")
                        .with(authentication(admin())))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/moderation/queue"))
                .andExpect(flash().attribute("error", NOT_SENT_MESSAGE));

        assertStillPending(saved);
        assertNothingLoggedNames(email);
    }

    @Test
    void page_emailFails_theQueueShowsTheMessage_andKeepsTheTypedReasonOnThatItemOnly() throws Exception {
        String email = "jane.page-reason@example.com";
        Testimonial other = pendingTestimonial("someone-else.page-reason@example.com");
        Testimonial saved = pendingTestimonial(email);
        mailServerRejects(email);
        String reason = "Please double-check the <housing> section & dates.";

        MvcResult rejected = mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()).with(csrfField())
                        .param("reason", reason)
                        .with(authentication(admin())))
                .andExpect(status().isFound())
                .andReturn();
        String queue = mockMvc.perform(get("/moderation/queue")
                        .flashAttrs(rejected.getFlashMap())
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(queue).contains(HtmlUtils.htmlEscape(NOT_SENT_MESSAGE));
        assertThat(textareaOf(queue, saved.getId())).contains(">" + HtmlUtils.htmlEscape(reason) + "</textarea>");
        assertThat(textareaOf(queue, other.getId())).endsWith("></textarea>");
        assertThat(queue).doesNotContain("<housing>");
    }

    @Test
    void page_queueWithoutAFailedReject_showsNoErrorBanner() throws Exception {
        pendingTestimonial("jane.page-no-banner@example.com");

        String queue = mockMvc.perform(get("/moderation/queue").with(authentication(admin())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(queue)
                .doesNotContain("class=\"moderation-error-banner\"")
                .doesNotContain(HtmlUtils.htmlEscape(NOT_SENT_MESSAGE))
                .doesNotContain("role=\"alert\"");
    }

    @Test
    void page_emailSent_rejectsAsBefore_withoutAnError() throws Exception {
        Testimonial saved = pendingTestimonial("jane.page-sent@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()).with(csrfField())
                        .param("reason", "Please add more detail.")
                        .with(authentication(admin())))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/moderation/queue"))
                .andExpect(flash().attributeCount(0));

        assertThat(testimonialRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(TestimonialStatus.REJECTED);
    }

    /** The reject reason's {@code <textarea>} of the queue item for {@code id}, as rendered. */
    private static String textareaOf(String html, Long id) {
        return elements(html, "textarea").stream()
                .filter(textarea -> textarea.contains("id=\"reason-" + id + "\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no reason textarea for item " + id));
    }

    @TestConfiguration
    static class SmtpMailerConfig {

        /** The production mailer, over the test's mocked {@link JavaMailSender}. */
        @Bean
        @Primary
        NotificationMailer smtpNotificationMailer(JavaMailSender mailSender) {
            return new SmtpNotificationMailer(mailSender, "no-reply@iitm-beacon.example");
        }
    }
}
