package com.iitm.beacon.domain.testimonial;

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
}
