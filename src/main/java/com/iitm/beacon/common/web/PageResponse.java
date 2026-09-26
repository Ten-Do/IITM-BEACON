package com.iitm.beacon.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Uniform paginated response envelope for list endpoints across feature
 * slices (e.g. gallery, moderation). {@code content} carries the caller's own
 * mapped DTO type, while page/size/totalElements are read straight off the
 * originating Spring Data {@link Page} — {@code content} and {@code
 * springPage} need not share element type.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {

    public static <T> PageResponse<T> of(List<T> content, Page<?> springPage) {
        return new PageResponse<>(content, springPage.getNumber(), springPage.getSize(), springPage.getTotalElements());
    }
}
