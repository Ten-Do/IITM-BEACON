package com.iitm.beacon.perf;

import java.util.List;

/**
 * The reference data a {@link PerfDataPlan} is planned from — the topics
 * (grouped or standalone), achievements and contact types a seeded
 * testimonial may use, by slug. {@link PerfDataSeeder#loadCatalog()} reads
 * it from the database; tests build their own.
 */
record PerfCatalog(List<TopicRef> topics, List<String> achievementSlugs, List<String> contactTypeSlugs) {

    PerfCatalog {
        topics = List.copyOf(topics);
        achievementSlugs = List.copyOf(achievementSlugs);
        contactTypeSlugs = List.copyOf(contactTypeSlugs);
    }

    /** A topic by slug, with the id of its group, or {@code null} for a standalone topic. */
    record TopicRef(String slug, Long groupId) {
    }
}
