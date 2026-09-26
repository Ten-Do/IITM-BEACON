package com.iitm.beacon.gallery;

import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Static {@link Specification} builders for the public gallery's
 * browse/filter query (decision 8). Combined in {@code GalleryService} via
 * {@link Specification#allOf(Specification[])}, which treats a {@code null}
 * member as a no-op — so every method here returns {@code null} instead of
 * an always-true predicate when its filter isn't active, rather than every
 * caller having to special-case "filter absent".
 */
final class TestimonialSpecifications {

    private TestimonialSpecifications() {
    }

    static Specification<Testimonial> statusIs(TestimonialStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    static Specification<Testimonial> countryIs(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return null;
        }
        String normalized = countryCode.trim().toUpperCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(root.get("country").get("code"), normalized);
    }

    /**
     * Matches a testimonial with at least one {@link TestimonialSection}
     * whose topic id is in {@code topicIds} — an already-expanded set (a
     * requested topic-group id has already been turned into its member
     * topic ids by {@code GalleryService.expandTopicIds}, decision 11).
     */
    static Specification<Testimonial> hasAnyTopic(List<Long> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<TestimonialSection> sectionRoot = subquery.from(TestimonialSection.class);
            subquery.select(sectionRoot.get("id"))
                    .where(
                            cb.equal(sectionRoot.get("testimonial"), root),
                            sectionRoot.get("topic").get("id").in(topicIds));
            return cb.exists(subquery);
        };
    }

    /**
     * Free-text match (decision 8) over section answer text and the
     * submitter's first/last name (decision 10). Uses {@code
     * cb.lower(...)} + {@code cb.like(...)} rather than a dialect-specific
     * {@code ilike}, for H2/Postgres portability.
     */
    static Specification<Testimonial> matchesQuery(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<TestimonialSection> sectionRoot = subquery.from(TestimonialSection.class);
            subquery.select(sectionRoot.get("id"))
                    .where(
                            cb.equal(sectionRoot.get("testimonial"), root),
                            cb.like(cb.lower(sectionRoot.get("answerText")), pattern));
            Predicate sectionMatch = cb.exists(subquery);
            Predicate firstNameMatch = cb.like(cb.lower(root.get("firstName")), pattern);
            Predicate lastNameMatch = cb.like(cb.lower(root.get("lastName")), pattern);
            return cb.or(sectionMatch, firstNameMatch, lastNameMatch);
        };
    }
}
