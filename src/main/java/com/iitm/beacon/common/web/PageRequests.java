package com.iitm.beacon.common.web;

import com.iitm.beacon.common.error.PageOutOfRangeException;
import org.springframework.data.domain.PageRequest;

/**
 * Builds the {@link PageRequest} of a paged list (the gallery and the
 * moderation queue, page and API alike) only for a page that can be
 * addressed: one whose first row's offset, page × size, fits an {@code int}
 * — the most Spring Data passes on to the query. A page beyond it is the
 * client's error, never a query that fails (BL-014's 500s).
 */
public final class PageRequests {

    private PageRequests() {
    }

    /** The last addressable page for {@code size} (at least 1). */
    public static int largestPage(int size) {
        return Integer.MAX_VALUE / size;
    }

    /**
     * {@code PageRequest.of(page, size)}, whose own checks still refuse a
     * negative page or a size below 1.
     *
     * @throws PageOutOfRangeException if {@code page} is beyond {@link #largestPage(int)}
     */
    public static PageRequest of(int page, int size) {
        if (size >= 1 && page > largestPage(size)) {
            throw new PageOutOfRangeException(largestPage(size));
        }
        return PageRequest.of(page, size);
    }
}
