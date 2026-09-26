package com.iitm.beacon.gallery;

import java.util.List;

/** A photo attached to a testimonial section, as rendered publicly (decision 2). */
public record PhotoRefDto(String url, List<String> tags) {
}
