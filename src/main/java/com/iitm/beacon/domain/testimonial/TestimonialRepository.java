package com.iitm.beacon.domain.testimonial;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestimonialRepository extends JpaRepository<Testimonial, Long> {

    Optional<Testimonial> findByEmailLookupHash(String emailLookupHash);
}
