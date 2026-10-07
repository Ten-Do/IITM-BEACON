package com.iitm.beacon.domain.topic;

import jakarta.persistence.Column;
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
 * Database-driven topic reference table (decision 11): {@code topicGroup} is
 * nullable — {@code null} means a standalone topic (e.g. {@code general}).
 */
@Entity
@Table(name = "topic")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Topic {

    /**
     * Slug of the mandatory standalone catch-all topic: pre-picked on the
     * submission form and protected from deactivation, deletion, regrouping
     * and slug changes in the catalog (decision 28).
     */
    public static final String GENERAL_SLUG = "general";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_group_id")
    private TopicGroup topicGroup;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "guiding_prompt", nullable = false)
    private String guidingPrompt;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * Cascaded visibility (decision 28): a topic is shown only when it is
     * active itself and its group, if any, is active too. Hidden topics keep
     * their data and reappear once reactivated.
     */
    public boolean isVisible() {
        return active && (topicGroup == null || topicGroup.isActive());
    }
}
