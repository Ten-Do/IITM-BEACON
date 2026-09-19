package com.iitm.beacon.domain.testimonial;

import com.iitm.beacon.common.crypto.EncryptedValueConverter;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.country.Country;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
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
 * A submitted testimonial (decisions 6, 10, 15, 18). {@code email} is
 * encrypted at rest; {@code emailLookupHash} is the deterministic HMAC used
 * only for the UC-VISITOR-LOGIN "does a testimonial already exist" check.
 */
@Entity
@Table(
        name = "testimonial",
        indexes = {@Index(name = "idx_testimonial_status_country", columnList = "status, country_code")})
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Testimonial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "roll_number", nullable = false)
    private String rollNumber;

    @Column(name = "admission_year", nullable = false)
    private Integer admissionYear;

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "email", nullable = false, length = 500)
    private String email;

    @Column(name = "email_lookup_hash", nullable = false, unique = true)
    private String emailLookupHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_code", nullable = false)
    private Country country;

    @Column(name = "recommendation_score", nullable = false)
    private Integer recommendationScore;

    @Column(name = "data_processing_consent", nullable = false)
    private boolean dataProcessingConsent;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TestimonialStatus status = TestimonialStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Builder.Default
    @Column(name = "identity_modified", nullable = false)
    private boolean identityModified = false;

    @Builder.Default
    @Column(name = "score_modified", nullable = false)
    private boolean scoreModified = false;

    @Builder.Default
    @OneToMany(mappedBy = "testimonial", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TestimonialSection> sections = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "testimonial", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ContactMethod> contactMethods = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "testimonial", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TestimonialAchievement> achievements = new ArrayList<>();
}
