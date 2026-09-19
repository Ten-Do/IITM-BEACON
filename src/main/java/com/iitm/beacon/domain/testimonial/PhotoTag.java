package com.iitm.beacon.domain.testimonial;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A free-form, submitter-entered tag on a {@link Photo} (decision 2), up to
 * 10 per photo (enforced server-side in a later milestone's
 * {@code SubmissionService}). Uses a surrogate {@code id}, for consistency
 * with every other child table in this schema, plus a {@code (photo_id,
 * tag_text)} uniqueness constraint as added integrity — no duplicate tag
 * text on the same photo.
 */
@Entity
@Table(
        name = "photo_tag",
        uniqueConstraints = {@UniqueConstraint(name = "uk_photo_tag_photo_id_tag_text", columnNames = {"photo_id", "tag_text"})})
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PhotoTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_id", nullable = false)
    private Photo photo;

    @Column(name = "tag_text", nullable = false)
    private String tagText;
}
