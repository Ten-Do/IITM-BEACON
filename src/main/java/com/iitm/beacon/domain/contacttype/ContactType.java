package com.iitm.beacon.domain.contacttype;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Seed-only contact-type reference data (decision 5) — same shape as
 * {@code Topic}/{@code Achievement} minus a guiding prompt or grouping, but
 * with no admin-catalog UI: a new type is a direct database edit, same
 * treatment as {@code Country}.
 */
@Entity
@Table(name = "contact_type")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class ContactType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    /** Display name shown to people, e.g. "WhatsApp". */
    @Column(name = "name", nullable = false)
    private String name;

    /** Input placeholder describing the accepted value, e.g. "phone number, or a wa.me link". */
    @Column(name = "label", nullable = false)
    private String label;

    /**
     * Regular expression (no {@code ^}/{@code $}) a trimmed contact value
     * must match in full, also rendered as the input's HTML {@code pattern};
     * {@code null} means only a non-blank value is required (decision 5).
     */
    @Column(name = "value_pattern")
    private String valuePattern;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
