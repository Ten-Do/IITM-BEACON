package com.iitm.beacon.domain.testimonial;

import com.iitm.beacon.domain.country.Country;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.support.PageableExecutionUtils;

/** {@link TestimonialSummaryQueries} as two criteria queries: the page, and its count. */
class TestimonialSummaryQueriesImpl implements TestimonialSummaryQueries {

    private final EntityManager entityManager;

    TestimonialSummaryQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<TestimonialSummary> findSummaries(Specification<Testimonial> spec, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TestimonialSummary> query = cb.createQuery(TestimonialSummary.class);
        Root<Testimonial> root = query.from(Testimonial.class);
        Join<Testimonial, Country> country = root.join("country");
        query.select(cb.construct(TestimonialSummary.class,
                root.get("id"), country.get("code"), country.get("name"), root.get("recommendationScore")));
        restrict(query, spec, root, cb);

        TypedQuery<TestimonialSummary> page = entityManager.createQuery(query);
        if (pageable.isPaged()) {
            page.setFirstResult(Math.toIntExact(pageable.getOffset()));
            page.setMaxResults(pageable.getPageSize());
        }
        return PageableExecutionUtils.getPage(page.getResultList(), pageable, () -> count(spec));
    }

    private long count(Specification<Testimonial> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<Testimonial> root = query.from(Testimonial.class);
        query.select(cb.count(root));
        restrict(query, spec, root, cb);
        // The specification may have set an order; a count has nothing to order.
        query.orderBy(List.of());
        return entityManager.createQuery(query).getSingleResult();
    }

    private static void restrict(
            CriteriaQuery<?> query, Specification<Testimonial> spec, Root<Testimonial> root, CriteriaBuilder cb) {
        Predicate predicate = spec.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
    }
}
