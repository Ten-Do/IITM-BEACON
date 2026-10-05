package com.iitm.beacon.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.contacttype.ContactType;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class GalleryServiceDetailTest {

    @Autowired
    private GalleryService galleryService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private Testimonial.TestimonialBuilder testimonial(String email) {
        Country india = countryRepository.findById("IN").orElseThrow();
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private Topic topic(String slug) {
        return topicRepository.findBySlug(slug).orElseThrow();
    }

    @Test
    void getDetail_approvedTestimonial_returnsOrderedSectionsAndCorrectFlags() {
        Testimonial t = testimonial("detail-happy@example.com")
                .status(TestimonialStatus.APPROVED)
                .build();
        // "general" (standalone, display_order 17) added first but must be
        // ordered after "academics_teaching" (group display_order 1).
        TestimonialSection general = TestimonialSection.builder()
                .testimonial(t)
                .topic(topic("general"))
                .answerText("General text.")
                .modified(false)
                .build();
        TestimonialSection academics = TestimonialSection.builder()
                .testimonial(t)
                .topic(topic("academics_teaching"))
                .answerText("Academics text.")
                .modified(true)
                .build();
        Photo photo = Photo.builder()
                .section(academics)
                .filePath("2026/01/pic.png")
                .displayOrder(0)
                .build();
        photo.getTags().add(com.iitm.beacon.domain.testimonial.PhotoTag.builder()
                .photo(photo)
                .tagText("campus")
                .build());
        academics.getPhotos().add(photo);
        t.getSections().add(general);
        t.getSections().add(academics);

        Achievement achievement = achievementRepository.findBySlug("made_new_friends").orElseThrow();
        t.getAchievements()
                .add(TestimonialAchievement.builder().testimonial(t).achievement(achievement).build());

        ContactType email = contactTypeRepository.findBySlug("email").orElseThrow();
        t.getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(t)
                        .contactType(email)
                        .value("visible@example.com")
                        .isPublic(true)
                        .displayOrder(0)
                        .build());

        Testimonial saved = testimonialRepository.saveAndFlush(t);

        TestimonialDetailDto dto = galleryService.getDetail(saved.getId());

        assertThat(dto.id()).isEqualTo(saved.getId());
        assertThat(dto.displayName()).isEqualTo("David J.");
        assertThat(dto.country().code()).isEqualTo("IN");
        assertThat(dto.recommendationScore()).isEqualTo(8);
        assertThat(dto.status()).isEqualTo("APPROVED");
        assertThat(dto.sections()).extracting(TestimonialSectionViewDto::topicSlug)
                .containsExactly("academics_teaching", "general");
        assertThat(dto.sections().get(0).updated()).isTrue();
        assertThat(dto.sections().get(1).updated()).isFalse();
        assertThat(dto.sections().get(0).photos()).hasSize(1);
        assertThat(dto.sections().get(0).photos().get(0).url()).isEqualTo("/uploads/2026/01/pic.png");
        assertThat(dto.sections().get(0).photos().get(0).tags()).containsExactly("campus");
        assertThat(dto.achievements()).containsExactly("made_new_friends");
        assertThat(dto.hasRevealableContact()).isTrue();
    }

    private Testimonial approvedWithPhotos(String email, Photo... photos) {
        Testimonial t = testimonial(email).status(TestimonialStatus.APPROVED).build();
        TestimonialSection general = TestimonialSection.builder()
                .testimonial(t)
                .topic(topic("general"))
                .answerText("Text.")
                .modified(false)
                .build();
        for (Photo photo : photos) {
            photo.setSection(general);
            general.getPhotos().add(photo);
        }
        t.getSections().add(general);
        return testimonialRepository.saveAndFlush(t);
    }

    @Test
    void getDetail_convertedPhoto_carriesItsThumbnailUrlAndSize() {
        Testimonial saved = approvedWithPhotos("detail-thumb@example.com", Photo.builder()
                .filePath("abc.webp")
                .thumbnailPath("abc-thumb.webp")
                .width(2560)
                .height(1707)
                .displayOrder(0)
                .build());

        PhotoRefDto photo = galleryService.getDetail(saved.getId()).sections().get(0).photos().get(0);

        assertThat(photo.url()).isEqualTo("/uploads/abc.webp");
        assertThat(photo.thumbnailUrl()).isEqualTo("/uploads/abc-thumb.webp");
        assertThat(photo.width()).isEqualTo(2560);
        assertThat(photo.height()).isEqualTo(1707);
    }

    @Test
    void getDetail_legacyPhotoWithoutThumbnail_fallsBackToTheFullUrl_andHasNoSize() {
        Testimonial saved = approvedWithPhotos("detail-legacy@example.com",
                Photo.builder().filePath("legacy.jpeg").displayOrder(0).build());

        PhotoRefDto photo = galleryService.getDetail(saved.getId()).sections().get(0).photos().get(0);

        assertThat(photo.url()).isEqualTo("/uploads/legacy.jpeg");
        assertThat(photo.thumbnailUrl()).isEqualTo("/uploads/legacy.jpeg");
        assertThat(photo.width()).isNull();
        assertThat(photo.height()).isNull();
    }

    @Test
    void getDetail_noPublicContactMethods_hasRevealableContactIsFalse() {
        Testimonial t = testimonial("detail-no-public-contact@example.com")
                .status(TestimonialStatus.APPROVED)
                .build();
        t.getSections()
                .add(TestimonialSection.builder()
                        .testimonial(t)
                        .topic(topic("general"))
                        .answerText("Text.")
                        .modified(false)
                        .build());
        ContactType email = contactTypeRepository.findBySlug("email").orElseThrow();
        t.getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(t)
                        .contactType(email)
                        .value("private@example.com")
                        .isPublic(false)
                        .displayOrder(0)
                        .build());
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        TestimonialDetailDto dto = galleryService.getDetail(saved.getId());

        assertThat(dto.hasRevealableContact()).isFalse();
    }

    @Test
    void getDetail_noContactMethodsAtAll_hasRevealableContactIsFalse() {
        Testimonial t = testimonial("detail-zero-contact@example.com")
                .status(TestimonialStatus.APPROVED)
                .build();
        t.getSections()
                .add(TestimonialSection.builder()
                        .testimonial(t)
                        .topic(topic("general"))
                        .answerText("Text.")
                        .modified(false)
                        .build());
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        TestimonialDetailDto dto = galleryService.getDetail(saved.getId());

        assertThat(dto.hasRevealableContact()).isFalse();
        assertThat(dto.achievements()).isEmpty();
    }

    @Test
    void getDetail_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> galleryService.getDetail(999_999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getDetail_pendingId_throwsNotFoundWithSameMessageAsUnknownId() {
        Testimonial pending = testimonial("detail-pending@example.com")
                .status(TestimonialStatus.PENDING)
                .build();
        pending.getSections()
                .add(TestimonialSection.builder()
                        .testimonial(pending)
                        .topic(topic("general"))
                        .answerText("Text.")
                        .modified(false)
                        .build());
        Testimonial saved = testimonialRepository.saveAndFlush(pending);

        String unknownMessage = catchMessage(() -> galleryService.getDetail(999_999L));
        String pendingMessage = catchMessage(() -> galleryService.getDetail(saved.getId()));

        assertThat(pendingMessage).isEqualTo(unknownMessage);
    }

    @Test
    void getDetail_rejectedId_throwsNotFoundWithSameMessageAsUnknownId() {
        Testimonial rejected = testimonial("detail-rejected@example.com")
                .status(TestimonialStatus.REJECTED)
                .build();
        rejected.getSections()
                .add(TestimonialSection.builder()
                        .testimonial(rejected)
                        .topic(topic("general"))
                        .answerText("Text.")
                        .modified(false)
                        .build());
        Testimonial saved = testimonialRepository.saveAndFlush(rejected);

        String unknownMessage = catchMessage(() -> galleryService.getDetail(999_999L));
        String rejectedMessage = catchMessage(() -> galleryService.getDetail(saved.getId()));

        assertThat(rejectedMessage).isEqualTo(unknownMessage);
    }

    private String catchMessage(Runnable action) {
        try {
            action.run();
        } catch (NotFoundException ex) {
            return ex.getMessage();
        }
        throw new AssertionError("Expected NotFoundException to be thrown");
    }
}
