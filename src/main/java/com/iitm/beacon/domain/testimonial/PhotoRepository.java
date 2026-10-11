package com.iitm.beacon.domain.testimonial;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    /**
     * Legacy photos (stored before thumbnails existed), in id order, starting
     * strictly after {@code afterId} — keyset paging, so a photo that can't be
     * converted is simply passed over instead of being returned again.
     */
    List<Photo> findByThumbnailPathIsNullAndIdGreaterThanOrderByIdAsc(Long afterId, Limit limit);

    /**
     * Points a legacy photo at its converted files — only if it is still the
     * same legacy photo (same {@code file_path}, still no thumbnail), so a
     * concurrent edit or delete is never overwritten. Touches no other column.
     *
     * @return 1 if the row was updated, 0 if it had changed or was gone
     */
    @Modifying
    @Query("UPDATE Photo p SET p.filePath = :newFilePath, p.thumbnailPath = :thumbnailPath,"
            + " p.width = :width, p.height = :height"
            + " WHERE p.id = :id AND p.filePath = :oldFilePath AND p.thumbnailPath IS NULL")
    int replaceLegacyFile(
            @Param("id") Long id,
            @Param("oldFilePath") String oldFilePath,
            @Param("newFilePath") String newFilePath,
            @Param("thumbnailPath") String thumbnailPath,
            @Param("width") int width,
            @Param("height") int height);

    /**
     * The photos of every section in {@code sectionIds}, section by section
     * in id order, each section's in display order (then id).
     */
    @Query("select new com.iitm.beacon.domain.testimonial.SectionPhoto("
            + " p.id, p.section.id, p.filePath, p.thumbnailPath, p.width, p.height)"
            + " from Photo p where p.section.id in :sectionIds order by p.section.id, p.displayOrder, p.id")
    List<SectionPhoto> findSectionPhotosBySectionIdIn(@Param("sectionIds") Collection<Long> sectionIds);

    /** Every photo of the sections of {@code sectionIds}, each with its tags loaded in the same query. */
    @Query("select p from Photo p left join fetch p.tags where p.section.id in :sectionIds")
    List<Photo> findWithTagsBySectionIdIn(@Param("sectionIds") Collection<Long> sectionIds);

    /**
     * Every photo of the testimonials of {@code testimonialIds}, in id order,
     * each with its section loaded in the same query — which testimonial a
     * photo belongs to costs no further statement.
     */
    @Query("select p from Photo p join fetch p.section s where s.testimonial.id in :testimonialIds order by p.id")
    List<Photo> findWithSectionByTestimonialIdIn(@Param("testimonialIds") Collection<Long> testimonialIds);
}
