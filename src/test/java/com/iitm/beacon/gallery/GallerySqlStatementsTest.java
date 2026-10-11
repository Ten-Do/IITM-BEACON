package com.iitm.beacon.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import com.iitm.beacon.testsupport.SampleTestimonials;
import com.iitm.beacon.testsupport.SampleTestimonials.Size;
import com.iitm.beacon.testsupport.SqlStatementCounter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * How many SQL statements the public gallery costs (BL-049): every listing —
 * whichever filters — article, contact reveal and filter catalog costs the
 * same for one small testimonial as for a page of large ones, so the count
 * never grows with the cards, sections, photos, tags, contacts or
 * achievement ticks shown, nor with the ids a topic filter names; and it
 * stays within a fixed bound.
 *
 * <p>A first page that isn't full needs no count query (Spring Data knows
 * the total from the page itself), so a listing of one card is compared on
 * a page of size 1 — full, like the page of 20 it is compared with. The
 * gallery page always shows 20, so there the page of 20 costs exactly that
 * one count query more than the page of one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GallerySqlStatementsTest {

    /** Page query, count query, card covers. */
    private static final long LISTING_BOUND = 3;

    /** The same, plus resolving the topic filter's ids. */
    private static final long TOPIC_FILTERED_LISTING_BOUND = LISTING_BOUND + 1;

    /** The testimonial, its sections, their photos, the photos' tags, achievement ticks, public contacts. */
    private static final long DETAIL_BOUND = 6;

    @Autowired
    private GalleryService galleryService;

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
     * The statements {@code action} costs, starting from an empty persistence
     * context and ending with a flush, as a request's transaction does.
     */
    private long statements(SqlStatementCounter.Action action) throws Exception {
        entityManager.flush();
        entityManager.clear();
        return counter.count(() -> {
            action.run();
            entityManager.flush();
        }).prepared();
    }

    private long browse(String country, List<Long> groupIds, List<Long> topicIds, String q, int size)
            throws Exception {
        return statements(() -> galleryService.browse(country, groupIds, topicIds, q, PageRequest.of(0, size)));
    }

    private Long groupOf(Topic topic) {
        TopicGroup group = topic.getTopicGroup();
        assertThat(group).as("sample topic %s is in a group", topic.getSlug()).isNotNull();
        return group.getId();
    }

    // -- the listing, every filter --

    @Test
    void browse_withoutFilters_costsTheSameForOneSmallCardAsForAPageOfLargeOnes() throws Exception {
        samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = browse(null, null, null, null, 1);
        samples.save(19, TestimonialStatus.APPROVED, Size.LARGE);
        long many = browse(null, null, null, null, 20);

        assertThat(many).as("a page of 20 large cards vs one small card (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(LISTING_BOUND);
    }

    @Test
    void browse_byCountry_costsTheSameForOneSmallCardAsForAPageOfLargeOnes() throws Exception {
        Country germany = countryRepository.findById("DE").orElseThrow();
        SampleTestimonials german = samples.inCountry(germany);
        german.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = browse("DE", null, null, null, 1);
        german.save(19, TestimonialStatus.APPROVED, Size.LARGE);
        long many = browse("DE", null, null, null, 20);

        assertThat(many).as("a page of 20 large cards vs one small card (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(LISTING_BOUND);
    }

    @Test
    void browse_byTopicGroup_costsTheSameForOneSmallCardAsForAPageOfLargeOnes() throws Exception {
        List<Long> groupIds = List.of(groupOf(samples.topic(0)));
        samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = browse(null, groupIds, null, null, 1);
        samples.save(19, TestimonialStatus.APPROVED, Size.LARGE);
        long many = browse(null, groupIds, null, null, 20);

        assertThat(many).as("a page of 20 large cards vs one small card (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(TOPIC_FILTERED_LISTING_BOUND);
    }

    @Test
    void browse_byTopic_costsTheSameForOneSmallCardAsForAPageOfLargeOnes() throws Exception {
        List<Long> topicIds = List.of(samples.topic(0).getId());
        samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = browse(null, null, topicIds, null, 1);
        samples.save(19, TestimonialStatus.APPROVED, Size.LARGE);
        long many = browse(null, null, topicIds, null, 20);

        assertThat(many).as("a page of 20 large cards vs one small card (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(TOPIC_FILTERED_LISTING_BOUND);
    }

    @Test
    void browse_bySearch_costsTheSameForOneSmallCardAsForAPageOfLargeOnes() throws Exception {
        samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = browse(null, null, null, "sample answer 0", 1);
        samples.save(19, TestimonialStatus.APPROVED, Size.LARGE);
        long many = browse(null, null, null, "sample answer 0", 20);

        assertThat(many).as("a page of 20 large cards vs one small card (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(LISTING_BOUND);
    }

    @Test
    void browse_byCountryGroupAndSearchTogether_costsTheSameForOneSmallCardAsForAPageOfLargeOnes() throws Exception {
        Country germany = countryRepository.findById("DE").orElseThrow();
        SampleTestimonials german = samples.inCountry(germany);
        List<Long> groupIds = List.of(groupOf(samples.topic(0)));
        german.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = browse("DE", groupIds, null, "sample", 1);
        german.save(19, TestimonialStatus.APPROVED, Size.LARGE);
        long many = browse("DE", groupIds, null, "sample", 20);

        assertThat(many).as("a page of 20 large cards vs one small card (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(TOPIC_FILTERED_LISTING_BOUND);
    }

    @Test
    void browse_topicFilter_costsTheSameForOneIdAsForManyIds_knownOrNot_visibleOrNot() throws Exception {
        CatalogVisibilityFixture catalog =
                CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
        samples.save(20, TestimonialStatus.APPROVED, Size.LARGE);
        List<Long> manyGroupIds = new ArrayList<>(topicGroupRepository.findAll().stream()
                .map(TopicGroup::getId)
                .toList());
        manyGroupIds.add(Long.MAX_VALUE);
        List<Long> manyTopicIds = new ArrayList<>(topicRepository.findAll().stream().map(Topic::getId).toList());
        manyTopicIds.add(Long.MAX_VALUE);
        assertThat(manyGroupIds).contains(catalog.inactiveGroup().getId());
        assertThat(manyTopicIds).contains(catalog.inactiveTopic().getId(), catalog.topicInInactiveGroup().getId());

        long oneGroup = browse(null, List.of(groupOf(samples.topic(0))), null, null, 20);
        long oneTopic = browse(null, null, List.of(samples.topic(0).getId()), null, 20);
        long manyIds = browse(null, manyGroupIds, manyTopicIds, null, 20);

        assertThat(manyIds).as("every group and topic id vs one group id (%s)", oneGroup).isEqualTo(oneGroup);
        assertThat(manyIds).as("every group and topic id vs one topic id (%s)", oneTopic).isEqualTo(oneTopic);
        assertThat(manyIds).isLessThanOrEqualTo(TOPIC_FILTERED_LISTING_BOUND);
    }

    // -- the article, the contact reveal --

    @Test
    void getDetail_costsTheSameForOneSectionPhotoTagContactAndTickAsForManyOfEach() throws Exception {
        Testimonial small = samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        Testimonial large = samples.save(TestimonialStatus.APPROVED, Size.LARGE);

        long one = statements(() -> galleryService.getDetail(small.getId()));
        long many = statements(() -> galleryService.getDetail(large.getId()));

        assertThat(many).as("a large article vs a small one (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(DETAIL_BOUND);
    }

    @Test
    void revealContact_costsTheSameForOnePublicContactAsForSeveral() throws Exception {
        Testimonial small = samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        Testimonial large = samples.save(TestimonialStatus.APPROVED, Size.LARGE);

        long one = statements(() -> assertThat(galleryService.revealContact(small.getId())).hasSize(1));
        long many = statements(() -> assertThat(galleryService.revealContact(large.getId())).hasSize(2));

        assertThat(many).as("several public contacts vs one (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(1);
    }

    // -- the filter catalog, the country list --

    @Test
    void listTopicCatalogWithApproved_costsTheSameHoweverManyGroupsTopicsAndTestimonials() throws Exception {
        samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long few = statements(galleryService::listTopicCatalogWithApproved);
        List<Topic> newTopics = new ArrayList<>();
        for (int g = 0; g < 4; g++) {
            TopicGroup group = topicGroupRepository.saveAndFlush(
                    TopicGroup.builder().label("Statement group " + g).displayOrder(300 + g).build());
            for (int t = 0; t < 3; t++) {
                newTopics.add(topicRepository.saveAndFlush(CatalogVisibilityFixture.topic(
                        "statement_topic_" + g + "_" + t, "Statement topic " + g + "." + t, group, t, true)));
            }
        }
        samples.withTopics(newTopics.subList(0, 5)).save(5, TestimonialStatus.APPROVED, Size.LARGE);
        samples.withTopics(newTopics.subList(5, 10)).save(5, TestimonialStatus.APPROVED, Size.LARGE);
        long many = statements(galleryService::listTopicCatalogWithApproved);

        assertThat(many).as("many more groups, topics and testimonials vs few (%s)", few).isEqualTo(few);
        assertThat(many).isLessThanOrEqualTo(2);
    }

    @Test
    void listCountriesWithApproved_costsTheSameForOneCountryAsForMany() throws Exception {
        samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = statements(galleryService::listCountriesWithApproved);
        samples.save(20, TestimonialStatus.APPROVED, Size.SMALLEST);
        long many = statements(galleryService::listCountriesWithApproved);

        assertThat(many).as("21 countries vs one (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(1);
    }

    // -- whole requests --

    @Test
    void galleryPage_costsOneCountQueryMoreForAFullPageOfLargeCardsThanForOneSmallCard() throws Exception {
        samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = statements(() -> mockMvc.perform(get("/gallery")).andExpect(status().isOk()));
        samples.save(19, TestimonialStatus.APPROVED, Size.LARGE);
        long many = statements(() -> mockMvc.perform(get("/gallery")).andExpect(status().isOk()));

        assertThat(many).as("a page of 20 large cards vs one small card (%s), plus the count query", one)
                .isEqualTo(one + 1);
    }

    @Test
    void galleryApi_costsTheSameForOneSmallCardAsForAPageOfLargeOnes() throws Exception {
        samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = statements(() -> mockMvc.perform(get("/api/gallery/testimonials").param("size", "1"))
                .andExpect(status().isOk()));
        samples.save(19, TestimonialStatus.APPROVED, Size.LARGE);
        long many = statements(() -> mockMvc.perform(get("/api/gallery/testimonials").param("size", "20"))
                .andExpect(status().isOk()));

        assertThat(many).as("a page of 20 large cards vs one small card (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(LISTING_BOUND);
    }

    @Test
    void articlePage_costsTheSameForASmallTestimonialAsForALargeOne() throws Exception {
        Testimonial small = samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        Testimonial large = samples.save(TestimonialStatus.APPROVED, Size.LARGE);

        long one = statements(() -> mockMvc.perform(get("/gallery/" + small.getId())).andExpect(status().isOk()));
        long many = statements(() -> mockMvc.perform(get("/gallery/" + large.getId())).andExpect(status().isOk()));

        assertThat(many).as("a large article vs a small one (%s)", one).isEqualTo(one);
    }
}
