package com.iitm.beacon.catalogadmin;

/** A topic group as the catalog admin sees it ({@code TopicGroup} in api-spec), inactive ones included. */
public record TopicGroupDto(Long id, String label, Integer displayOrder, boolean active) {
}
