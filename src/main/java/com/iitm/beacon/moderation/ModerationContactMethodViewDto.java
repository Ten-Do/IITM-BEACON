package com.iitm.beacon.moderation;

/**
 * A contact method as seen by the admin — includes private entries too,
 * unlike the public gallery's {@code ContactMethodView} (decisions 5, 6).
 */
public record ModerationContactMethodViewDto(String type, String value, boolean isPublic) {
}
