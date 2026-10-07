package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTag;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.Csrf;
import com.iitm.beacon.testsupport.LoginCodeSteps;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * The headers' "Log out" button: a POST form to {@code /logout} carrying the
 * CSRF token, inside the header's nav — so on a phone it sits in the burger
 * menu with the other items. The admin header always has it (every page
 * using it needs the admin login); the visitor header only for a browser
 * that is logged in — as a visitor, or as an admin looking at a public page.
 * The admin login pages' own header has none, and neither has the site's
 * error page, which is rendered without the request (common.error
 * .ErrorPageRenderer).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class HeaderLogoutButtonTest {

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
                "header-logout@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private Long approvedTestimonial() {
        Testimonial t = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email("header-logout-detail@example.com")
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
        return testimonialRepository.saveAndFlush(t).getId();
    }

    /** Every form on the page that posts to {@code /logout}. */
    private static List<String> logoutForms(String html) {
        return elements(html, "form").stream()
                .filter(form -> attribute(openingTag(form, "form"), "action").filter("/logout"::equals).isPresent())
                .toList();
    }

    /**
     * Exactly one logout form, inside the header's nav {@code navId}: a POST
     * with the CSRF token and one submit button, "Log out".
     */
    private static void assertOneLogoutButtonInTheNav(String html, String navId) {
        List<String> navs = elements(html, "nav").stream()
                .filter(nav -> attribute(openingTag(nav, "nav"), "id").filter(navId::equals).isPresent())
                .toList();
        assertThat(navs).as("#" + navId).hasSize(1);
        assertThat(logoutForms(html)).singleElement().satisfies(form -> {
            assertThat(navs.get(0)).contains(form);
            assertThat(attribute(openingTag(form, "form"), "method")).contains("post");
            assertThat(Csrf.hiddenFieldValues(form)).singleElement()
                    .satisfies(token -> assertThat(token).isNotBlank());
            assertThat(openingTags(form, "button")).singleElement()
                    .satisfies(button -> assertThat(attribute(button, "type")).contains("submit"));
            assertThat(elements(form, "button").get(0)).contains(">Log out</button>");
        });
    }

    // -- visitor header --

    @ParameterizedTest
    @ValueSource(strings = {"/", "/gallery", "/submissions/login", "/submissions/login/code"})
    void visitorHeader_browserNotLoggedIn_hasNoLogoutButton(String path) throws Exception {
        assertThat(logoutForms(html(LoginCodeSteps.page(mockMvc, path)))).isEmpty();
    }

    @Test
    void visitorHeader_onAnArticle_browserNotLoggedIn_hasNoLogoutButton() throws Exception {
        assertThat(logoutForms(html(get("/gallery/{id}", approvedTestimonial())))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/gallery", "/gallery/999999", "/submissions/form", "/submissions/confirmation"})
    void visitorHeader_visitorSession_hasTheLogoutButtonInTheMenu(String path) throws Exception {
        assertOneLogoutButtonInTheNav(html(get(path).with(authentication(visitor()))), "visitor-nav");
    }

    @Test
    void visitorHeader_onAnArticle_visitorSession_hasTheLogoutButtonInTheMenu() throws Exception {
        assertOneLogoutButtonInTheNav(
                html(get("/gallery/{id}", approvedTestimonial()).with(authentication(visitor()))), "visitor-nav");
    }

    /** Logging out works the same for an admin, who may look at the public pages in the same browser. */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/gallery", "/submissions/login"})
    void visitorHeader_adminSession_hasTheLogoutButtonInTheMenu(String path) throws Exception {
        assertOneLogoutButtonInTheNav(html(get(path).with(authentication(admin()))), "visitor-nav");
    }

    /** The error page is rendered without the request, so it can't know the browser is logged in (BL-054). */
    @Test
    void errorPage_hasNoLogoutButton_evenForALoggedInVisitor() throws Exception {
        String html = html(delete("/submissions/login/code").with(Csrf.csrfField()).with(authentication(visitor())));

        assertThat(html).contains("Action not allowed");
        assertThat(logoutForms(html)).isEmpty();
    }

    // -- admin header --

    @ParameterizedTest
    @ValueSource(strings = {"/moderation/queue", "/catalog/topics", "/catalog/achievements", "/catalog/topics/new"})
    void adminHeader_alwaysHasTheLogoutButtonInTheMenu(String path) throws Exception {
        assertOneLogoutButtonInTheNav(html(get(path).with(authentication(admin()))), "admin-nav");
    }

    @Test
    void adminHeader_logoutButtonComesAfterTheNavLinks() throws Exception {
        String nav = elements(html(get("/moderation/queue").with(authentication(admin()))), "nav").get(0);

        assertThat(nav.indexOf("Achievements</a>")).isLessThan(nav.indexOf("action=\"/logout\""));
    }

    // -- the admin login pages: their own header, no nav --

    @ParameterizedTest
    @ValueSource(strings = {"/admin/login", "/admin/login/code"})
    void adminLoginPages_haveNoLogoutButton(String path) throws Exception {
        assertThat(logoutForms(html(LoginCodeSteps.page(mockMvc, path)))).isEmpty();
    }

    @Test
    void adminLoginPage_withAnAdminSession_stillHasNoLogoutButton() throws Exception {
        assertThat(logoutForms(html(get("/admin/login").with(authentication(admin()))))).isEmpty();
    }
}
