package com.iitm.beacon.gallery;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.contacttype.ContactType;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link GalleryController} — every endpoint here is
 * public (no auth), so no authentication mocking is needed. Security
 * filters are left enabled (real {@code SecurityConfig}, no {@code
 * addFilters = false}) so these tests prove the actual {@code
 * .requestMatchers(HttpMethod.GET, "/api/gallery/**").permitAll()} rule
 * works, not just the controller logic in isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class GalleryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private Testimonial approvedTestimonialWithPublicContact(String email) {
        Country india = countryRepository.findById("IN").orElseThrow();
        Topic general = topicRepository.findBySlug("general").orElseThrow();
        Testimonial t = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        t.getSections()
                .add(TestimonialSection.builder()
                        .testimonial(t)
                        .topic(general)
                        .answerText("Great time overall.")
                        .modified(false)
                        .build());
        ContactType emailType = contactTypeRepository.findBySlug("email").orElseThrow();
        t.getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(t)
                        .contactType(emailType)
                        .value("public@example.com")
                        .isPublic(true)
                        .displayOrder(0)
                        .build());
        return testimonialRepository.saveAndFlush(t);
    }

    @Test
    void browse_returnsPageResponseOfApprovedTestimonials() throws Exception {
        Testimonial saved = approvedTestimonialWithPublicContact("controller-browse@example.com");

        mockMvc.perform(get("/api/gallery/testimonials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + saved.getId() + ")]").exists())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void browse_sizeZero_returns400() throws Exception {
        mockMvc.perform(get("/api/gallery/testimonials").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void browse_sizeAboveMax_returns400() throws Exception {
        mockMvc.perform(get("/api/gallery/testimonials").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void browse_sizeAtMax_returns200() throws Exception {
        mockMvc.perform(get("/api/gallery/testimonials").param("size", "100"))
                .andExpect(status().isOk());
    }

    @Test
    void browse_negativePage_returns400() throws Exception {
        mockMvc.perform(get("/api/gallery/testimonials").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void browse_pageZero_returns200() throws Exception {
        mockMvc.perform(get("/api/gallery/testimonials").param("page", "0"))
                .andExpect(status().isOk());
    }

    @Test
    void browse_countryCodeWrongLength_returns400() throws Exception {
        mockMvc.perform(get("/api/gallery/testimonials").param("country", "IND"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void browse_withFilters_returns200() throws Exception {
        Topic general = topicRepository.findBySlug("general").orElseThrow();
        mockMvc.perform(get("/api/gallery/testimonials")
                        .param("country", "IN")
                        .param("topicIds", String.valueOf(general.getId()))
                        .param("q", "great"))
                .andExpect(status().isOk());
    }

    @Test
    void detail_approvedTestimonial_returns200WithExpectedShape() throws Exception {
        Testimonial saved = approvedTestimonialWithPublicContact("controller-detail@example.com");

        mockMvc.perform(get("/api/gallery/testimonials/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("David J."))
                .andExpect(jsonPath("$.hasRevealableContact").value(true))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void detail_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/gallery/testimonials/{id}", 999_999L)).andExpect(status().isNotFound());
    }

    @Test
    void detail_pendingTestimonial_returns404() throws Exception {
        Country india = countryRepository.findById("IN").orElseThrow();
        Topic general = topicRepository.findBySlug("general").orElseThrow();
        Testimonial pending = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email("controller-detail-pending@example.com")
                .emailLookupHash(emailLookupHashService.hash("controller-detail-pending@example.com"))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        pending.getSections()
                .add(TestimonialSection.builder()
                        .testimonial(pending)
                        .topic(general)
                        .answerText("Text.")
                        .modified(false)
                        .build());
        Testimonial saved = testimonialRepository.saveAndFlush(pending);

        mockMvc.perform(get("/api/gallery/testimonials/{id}", saved.getId())).andExpect(status().isNotFound());
    }

    @Test
    void contact_approvedWithPublicContact_returns200WithPublicEntries() throws Exception {
        Testimonial saved = approvedTestimonialWithPublicContact("controller-contact@example.com");

        mockMvc.perform(get("/api/gallery/testimonials/{id}/contact", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("email"))
                .andExpect(jsonPath("$[0].value").value("public@example.com"));
    }

    @Test
    void contact_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/gallery/testimonials/{id}/contact", 999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void countries_returns200WithSeededApprovedCountry() throws Exception {
        approvedTestimonialWithPublicContact("controller-countries@example.com");

        mockMvc.perform(get("/api/gallery/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'IN')]").exists());
    }

    @Test
    void countries_noApprovedTestimonials_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/gallery/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void topics_returns200WithQualifyingGroupNested() throws Exception {
        approvedTestimonialWithPublicContact("controller-topics@example.com");

        mockMvc.perform(get("/api/gallery/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'STANDALONE' && @.slug == 'general')]").exists());
    }

    @Test
    void topics_noApprovedSections_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/gallery/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
