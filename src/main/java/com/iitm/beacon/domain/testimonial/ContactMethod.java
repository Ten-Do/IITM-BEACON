package com.iitm.beacon.domain.testimonial;

import com.iitm.beacon.common.crypto.EncryptedValueConverter;
import com.iitm.beacon.domain.contacttype.ContactType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A submitter-added contact entry (decision 5). {@code value} is encrypted
 * at rest via the same {@link EncryptedValueConverter} as {@code
 * Testimonial.email}; {@code isPublic} is the submitter's own per-entry
 * choice, independent of the testimonial's approval status.
 */
@Entity
@Table(name = "contact_method")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class ContactMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "testimonial_id", nullable = false)
    private Testimonial testimonial;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_type_id", nullable = false)
    private ContactType contactType;

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "value", nullable = false, length = 500)
    private String value;

    @Builder.Default
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;
}
