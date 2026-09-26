package com.iitm.beacon.config;

import org.springframework.stereotype.Component;

/**
 * Resolves a stored photo's root-relative file path to the public URL it is
 * served under (decision 2, docs/architecture.md §7).
 */
@Component
public class PhotoUrlResolver {

    public String resolve(String relativePath) {
        return "/uploads/" + relativePath;
    }
}
