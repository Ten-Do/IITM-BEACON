package com.iitm.beacon.domain.testimonial;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhotoTagRepository extends JpaRepository<PhotoTag, Long> {

    /** The tags of every photo in {@code photoIds}, photo by photo in id order, each photo's in the order added. */
    @Query("select new com.iitm.beacon.domain.testimonial.PhotoTagText(t.photo.id, t.tagText)"
            + " from PhotoTag t where t.photo.id in :photoIds order by t.photo.id, t.id")
    List<PhotoTagText> findTextsByPhotoIdIn(@Param("photoIds") Collection<Long> photoIds);
}
