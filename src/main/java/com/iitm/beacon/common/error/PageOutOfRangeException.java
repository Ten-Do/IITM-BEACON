package com.iitm.beacon.common.error;

/**
 * A requested page beyond the last one that can be addressed for its page
 * size — the offset of its first row, page × size, would not fit an {@code
 * int} ({@code common.web.PageRequests}). The client's error, answered with a
 * 400 whose message names the largest page there could be.
 */
public class PageOutOfRangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PageOutOfRangeException(int largestPage) {
        super("page: must be less than or equal to " + largestPage);
    }
}
