package com.iitm.beacon.domain.testimonial;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContactMethodRepository extends JpaRepository<ContactMethod, Long> {

    /**
     * Every contact method — public or not — of every testimonial in {@code
     * testimonialIds}, testimonial by testimonial in id order, each
     * testimonial's in display order (then id).
     */
    @Query("select new com.iitm.beacon.domain.testimonial.ContactMethodValue("
            + " cm.testimonial.id, ct.slug, cm.value, cm.isPublic)"
            + " from ContactMethod cm join cm.contactType ct where cm.testimonial.id in :testimonialIds"
            + " order by cm.testimonial.id, cm.displayOrder, cm.id")
    List<ContactMethodValue> findValuesByTestimonialIdIn(@Param("testimonialIds") Collection<Long> testimonialIds);

    /**
     * The public contact methods of testimonial {@code testimonialId}, in
     * display order (then id) — none at all unless it has {@code status}.
     */
    @Query("select new com.iitm.beacon.domain.testimonial.ContactMethodValue(t.id, ct.slug, cm.value, cm.isPublic)"
            + " from ContactMethod cm join cm.testimonial t join cm.contactType ct"
            + " where t.id = :testimonialId and t.status = :status and cm.isPublic = true"
            + " order by cm.displayOrder, cm.id")
    List<ContactMethodValue> findPublicValuesByTestimonialIdAndStatus(
            @Param("testimonialId") Long testimonialId, @Param("status") TestimonialStatus status);

    /** Whether testimonial {@code testimonialId} has at least one public contact method. */
    boolean existsByTestimonialIdAndIsPublicTrue(Long testimonialId);
}
