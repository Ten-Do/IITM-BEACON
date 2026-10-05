package com.iitm.beacon.domain.testimonial;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A photo attached to a {@link TestimonialSection} (decision 2). {@code
 * filePath} (the full-size image) and {@code thumbnailPath} are relative to
 * the mounted uploads volume root; the automatic topic tag is inherited from
 * its section, not stored redundantly here.
 */
@Entity
@Table(name = "photo")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "testimonial_section_id", nullable = false)
    private TestimonialSection section;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    /**
     * The thumbnail WebP, relative to the uploads root like {@code
     * filePath}; {@code null} for a legacy photo stored before thumbnails.
     */
    @Column(name = "thumbnail_path")
    private String thumbnailPath;

    /** Full-size image width in pixels; {@code null} for a legacy photo. */
    @Column(name = "width")
    private Integer width;

    /** Full-size image height in pixels; {@code null} for a legacy photo. */
    @Column(name = "height")
    private Integer height;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Builder.Default
    @OneToMany(mappedBy = "photo", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PhotoTag> tags = new ArrayList<>();
}
