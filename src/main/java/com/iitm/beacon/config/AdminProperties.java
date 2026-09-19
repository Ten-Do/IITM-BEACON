package com.iitm.beacon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code admin.*} config — the single admin account's email (decision
 * 4). No password, no {@code User} table.
 */
@ConfigurationProperties(prefix = "admin")
public record AdminProperties(String email) {
}
