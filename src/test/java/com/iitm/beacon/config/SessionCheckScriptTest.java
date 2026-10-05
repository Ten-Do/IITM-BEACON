package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which pages load {@code static/js/session-check.js}, and with which ping
 * URL: every page that needs a login does — so an expired session is noticed
 * as soon as the user returns to the tab — and no other page does. Admin
 * pages get it through {@code layout/header-admin.html}; the visitor's
 * protected pages include it themselves, because {@code
 * layout/header-visitor.html} is shared with the public and login pages. On
 * a login page it would be pointless at best and a reload loop at worst.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class SessionCheckScriptTest {

    private static final Pattern SESSION_CHECK_SCRIPT =
            Pattern.compile("<script\\b[^>]*\\bsrc=\"/js/session-check\\.js\"[^>]*>");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication visitor() {
        return new UsernamePasswordAuthenticationToken(
                "session-check-script@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private List<String> sessionCheckScriptTags(MockHttpServletRequestBuilder request) throws Exception {
        String html = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Matcher m = SESSION_CHECK_SCRIPT.matcher(html);
        List<String> tags = new ArrayList<>();
        while (m.find()) {
            tags.add(m.group());
        }
        return tags;
    }

    private Testimonial approvedTestimonial() {
        Testimonial t = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email("session-check-detail@example.com")
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        t.getSections().add(TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("Great time overall.")
                .modified(false)
                .build());
        return testimonialRepository.saveAndFlush(t);
    }

    // -- pages that need a login --

    @Test
    void moderationQueue_loadsTheScriptOnceDeferredWithTheAdminPingUrl() throws Exception {
        List<String> tags = sessionCheckScriptTags(get("/moderation/queue").with(authentication(admin())));

        assertThat(tags).singleElement().satisfies(tag -> {
            assertThat(tag).contains("data-session-url=\"/api/moderation/session\"");
            assertThat(tag).containsPattern("\\sdefer[\\s>=]");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"/submissions/form", "/submissions/confirmation"})
    void visitorProtectedPage_loadsTheScriptOnceDeferredWithTheVisitorPingUrl(String path) throws Exception {
        List<String> tags = sessionCheckScriptTags(get(path).with(authentication(visitor())));

        assertThat(tags).singleElement().satisfies(tag -> {
            assertThat(tag).contains("data-session-url=\"/api/submissions/session\"");
            assertThat(tag).containsPattern("\\sdefer[\\s>=]");
        });
    }

    // -- pages that don't --

    @ParameterizedTest
    @ValueSource(strings = {
        "/admin/login",
        "/admin/login/code?email=a@example.com",
        "/submissions/login",
        "/submissions/login/code?email=a@example.com",
        "/gallery",
    })
    void loginAndPublicPages_doNotLoadTheScript(String path) throws Exception {
        assertThat(sessionCheckScriptTags(get(path))).isEmpty();
    }

    @Test
    void galleryDetailPage_doesNotLoadTheScript() throws Exception {
        Testimonial saved = approvedTestimonial();

        assertThat(sessionCheckScriptTags(get("/gallery/{id}", saved.getId()))).isEmpty();
    }

    @Test
    void publicPageViewedWithAVisitorSession_stillDoesNotLoadTheScript() throws Exception {
        // Its content doesn't depend on the session, so there's nothing to re-check.
        assertThat(sessionCheckScriptTags(get("/gallery").with(authentication(visitor())))).isEmpty();
    }
}
