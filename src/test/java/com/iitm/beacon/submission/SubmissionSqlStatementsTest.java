package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.ContactMethodInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import com.iitm.beacon.testsupport.SampleTestimonials;
import com.iitm.beacon.testsupport.SampleTestimonials.Size;
import com.iitm.beacon.testsupport.SqlStatementCounter;
import com.iitm.beacon.testsupport.SqlStatementCounter.Statements;
import com.iitm.beacon.testsupport.TestImages;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * How many SQL statements the submission side costs (BL-049): the
 * visitor's own testimonial — {@code loadMine} and the edit form — loads in
 * the same statements whatever its size; creating and editing one cost the
 * same statements besides the rows they write, for one of everything as for
 * many; and the topic picker costs the same however big the catalog.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SubmissionSqlStatementsTest {

    /** The testimonial, its sections, their photos, the photos' tags, achievement ticks, contact methods. */
    private static final long LOAD_MINE_BOUND = 6;

    /** The existing-testimonial check, the country, the achievements, the contact types, the topics. */
    private static final long CREATE_BOUND = 5;

    /**
     * The testimonial with its sections' topics, the sections' photos, their
     * tags, contact methods, achievement ticks — then the same reference
     * lookups as a create but the existing-testimonial check.
     */
    private static final long EDIT_BOUND = 9;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private PhotoStorageService photoStorageService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private SqlStatementCounter counter;
    private SampleTestimonials samples;

    @BeforeEach
    void setUp() {
        counter = new SqlStatementCounter(entityManagerFactory);
        samples = new SampleTestimonials(testimonialRepository, topicRepository, countryRepository,
                contactTypeRepository, achievementRepository, emailLookupHashService);
    }

    /**
     * What {@code action} costs, starting from an empty persistence context
     * and ending with a flush, as a request's transaction does.
     */
    private Statements count(SqlStatementCounter.Action action) throws Exception {
        entityManager.flush();
        entityManager.clear();
        return counter.count(() -> {
            action.run();
            entityManager.flush();
        });
    }

    // -- the visitor's own testimonial --

    @Test
    void loadMine_costsTheSameForASmallTestimonialAsForALargeOne() throws Exception {
        Testimonial small = samples.save(TestimonialStatus.PENDING, Size.SMALLEST);
        Testimonial large = samples.save(TestimonialStatus.APPROVED, Size.LARGE);

        long one = count(() -> submissionService.loadMine(small.getEmail())).prepared();
        long many = count(() -> submissionService.loadMine(large.getEmail())).prepared();

        assertThat(many).as("a large testimonial vs a small one (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(LOAD_MINE_BOUND);
    }

    @Test
    void editFormPage_costsTheSameForASmallTestimonialAsForALargeOne() throws Exception {
        Testimonial small = samples.save(TestimonialStatus.PENDING, Size.SMALLEST);
        Testimonial large = samples.save(TestimonialStatus.APPROVED, Size.LARGE);

        long one = count(() -> mockMvc.perform(get("/submissions/form").with(authentication(visitor(small))))
                .andExpect(status().isOk())).prepared();
        long many = count(() -> mockMvc.perform(get("/submissions/form").with(authentication(visitor(large))))
                .andExpect(status().isOk())).prepared();

        assertThat(many).as("a large testimonial vs a small one (%s)", one).isEqualTo(one);
    }

    private static UsernamePasswordAuthenticationToken visitor(Testimonial testimonial) {
        return new UsernamePasswordAuthenticationToken(
                testimonial.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    // -- create --

    @Test
    void create_costsTheSameBesidesItsInsertsForOneOfEverythingAsForManyOfEach() throws Exception {
        Map<String, MultipartFile> oneFile = new HashMap<>();
        TestimonialSubmissionRequest oneOfEach = newRequest(1, 1, 1, 1, 1, oneFile);
        Map<String, MultipartFile> manyFiles = new HashMap<>();
        TestimonialSubmissionRequest manyOfEach = newRequest(5, 3, 3, 4, 4, manyFiles);

        Statements one = count(() -> submissionService.create(newEmail(), oneOfEach, oneFile));
        Statements many = count(() -> submissionService.create(newEmail(), manyOfEach, manyFiles));

        assertThat(many.entityInserts()).isGreaterThan(one.entityInserts());
        assertThat(many.withoutEntityWrites())
                .as("everything but the inserts, many of each vs one of each (%s)", one.withoutEntityWrites())
                .isEqualTo(one.withoutEntityWrites());
        assertThat(many.withoutEntityWrites()).isLessThanOrEqualTo(CREATE_BOUND);
    }

    private static String newEmail() {
        return "statements-" + UUID.randomUUID() + "@example.com";
    }

    /**
     * A new submission: {@code sections} sections under the sample topics,
     * each with {@code photos} new photos of {@code tags} tags (their files
     * put into {@code files}), {@code contacts} contacts of different types
     * and {@code achievements} ticks.
     */
    private TestimonialSubmissionRequest newRequest(
            int sections, int photos, int tags, int contacts, int achievements, Map<String, MultipartFile> files) {
        List<SectionInput> sectionInputs = new ArrayList<>();
        for (int s = 0; s < sections; s++) {
            List<PhotoInput> photoInputs = new ArrayList<>();
            for (int p = 0; p < photos; p++) {
                String ref = "photo-" + s + "-" + p;
                files.put(ref, new MockMultipartFile(ref, ref + ".png", "image/png",
                        TestImages.png(TestImages.solid(4, 3, TestImages.BLUE))));
                photoInputs.add(new PhotoInput(ref, Stream.iterate(0, t -> t + 1).limit(tags)
                        .map(t -> "tag " + t).toList()));
            }
            sectionInputs.add(new SectionInput(samples.topic(s).getSlug(), "New answer " + s + ".", photoInputs));
        }
        List<ContactMethodInput> contactInputs = Stream.iterate(0, c -> c + 1).limit(contacts)
                .map(samples::contactTypeSlug)
                .map(slug -> new ContactMethodInput(slug, SampleTestimonials.contactValue(slug), true))
                .toList();
        List<String> achievementSlugs =
                Stream.iterate(0, a -> a + 1).limit(achievements).map(samples::achievementSlug).toList();
        return new TestimonialSubmissionRequest("Sample", "Person", "GE26Z001", 2024, "DE", 7, sectionInputs,
                achievementSlugs, contactInputs, true);
    }

    // -- edit --

    @Test
    void edit_costsTheSameBesidesItsWritesForASmallTestimonialAsForALargeOne() throws Exception {
        Testimonial small = samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        Testimonial large = samples.save(TestimonialStatus.APPROVED, Size.LARGE);
        TestimonialSubmissionRequest smallEdit = everyAnswerRewordedAndEveryPhotoOneTagMore(small);
        TestimonialSubmissionRequest largeEdit = everyAnswerRewordedAndEveryPhotoOneTagMore(large);

        Statements one = count(() -> submissionService.edit(small.getEmail(), smallEdit, Map.of()));
        Statements many = count(() -> submissionService.edit(large.getEmail(), largeEdit, Map.of()));

        assertThat(many.withoutEntityWrites())
                .as("everything but the row writes, a large testimonial vs a small one (%s)",
                        one.withoutEntityWrites())
                .isEqualTo(one.withoutEntityWrites());
        assertThat(many.withoutEntityWrites()).isLessThanOrEqualTo(EDIT_BOUND);
    }

    /**
     * {@code testimonial} as saved, sent back with every answer reworded and
     * one more tag on every (kept) photo — so every section, photo, contact
     * and achievement tick is looked at.
     */
    private TestimonialSubmissionRequest everyAnswerRewordedAndEveryPhotoOneTagMore(Testimonial testimonial) {
        List<SectionInput> sections = new ArrayList<>();
        for (TestimonialSection section : testimonial.getSections()) {
            List<PhotoInput> photos = new ArrayList<>();
            for (Photo photo : section.getPhotos()) {
                List<String> tags = new ArrayList<>(photo.getTags().stream().map(PhotoTag::getTagText).toList());
                tags.add("one more tag");
                photos.add(new PhotoInput(photoStorageService.urlFor(photo.getFilePath()), tags));
            }
            sections.add(new SectionInput(section.getTopic().getSlug(), section.getAnswerText() + " Edited.", photos));
        }
        List<ContactMethodInput> contacts = testimonial.getContactMethods().stream()
                .map(cm -> new ContactMethodInput(cm.getContactType().getSlug(), cm.getValue(), cm.isPublic()))
                .toList();
        List<String> achievementSlugs = testimonial.getAchievements().stream()
                .map(TestimonialAchievement::getAchievement)
                .map(Achievement::getSlug)
                .toList();
        return new TestimonialSubmissionRequest(testimonial.getFirstName(), testimonial.getLastName(),
                testimonial.getRollNumber(), testimonial.getAdmissionYear(), testimonial.getCountry().getCode(),
                testimonial.getRecommendationScore(), sections, achievementSlugs, contacts, true);
    }

    // -- the topic picker --

    @Test
    void listTopicCatalog_costsTheSameHoweverManyGroupsAndTopics() throws Exception {
        long few = count(submissionService::listTopicCatalog).prepared();
        for (int g = 0; g < 4; g++) {
            TopicGroup group = topicGroupRepository.saveAndFlush(
                    TopicGroup.builder().label("Statement group " + g).displayOrder(300 + g).build());
            for (int t = 0; t < 3; t++) {
                Topic topic = CatalogVisibilityFixture.topic(
                        "statement_topic_" + g + "_" + t, "Statement topic " + g + "." + t, group, t, true);
                topicRepository.saveAndFlush(topic);
            }
        }
        long many = count(submissionService::listTopicCatalog).prepared();

        assertThat(many).as("four more groups of three topics vs the seeded catalog (%s)", few).isEqualTo(few);
        assertThat(many).isLessThanOrEqualTo(1);
    }
}
