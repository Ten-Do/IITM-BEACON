package com.iitm.beacon.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.error.PageOutOfRangeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageRequest;

/**
 * {@link PageRequests}: a page is addressable while the offset of its first
 * row, page × size, fits an {@code int} — the most Spring Data passes to the
 * query. One page further is the client's error ({@link
 * PageOutOfRangeException}, a 400), never a query that fails.
 */
class PageRequestsTest {

    @ParameterizedTest
    @CsvSource({"0, 1", "0, 100", "21474836, 100", "107374182, 20", "1073741823, 2", "2147483647, 1"})
    void addressablePage_isThatPageRequest(int page, int size) {
        PageRequest request = PageRequests.of(page, size);

        assertThat(request).isEqualTo(PageRequest.of(page, size));
        assertThat(request.getOffset()).isLessThanOrEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @CsvSource({"21474837, 100, 21474836", "107374183, 20, 107374182", "1073741824, 2, 1073741823",
        "2147483647, 100, 21474836", "2147483647, 2, 1073741823"})
    void onePageFurther_isOutOfRange_namingTheLargestPage(int page, int size, int largest) {
        assertThatThrownBy(() -> PageRequests.of(page, size))
                .isInstanceOf(PageOutOfRangeException.class)
                .hasMessage("page: must be less than or equal to " + largest);
    }

    @Test
    void largestPage_isTheLastWhoseOffsetFitsAnInt() {
        assertThat(PageRequests.largestPage(1)).isEqualTo(Integer.MAX_VALUE);
        assertThat(PageRequests.largestPage(20)).isEqualTo(107_374_182);
        assertThat(PageRequests.largestPage(100)).isEqualTo(21_474_836);
        assertThat((long) PageRequests.largestPage(100) * 100).isLessThanOrEqualTo(Integer.MAX_VALUE);
        assertThat((long) (PageRequests.largestPage(100) + 1) * 100).isGreaterThan(Integer.MAX_VALUE);
    }

    /** A negative page or a size below 1 is still {@code PageRequest}'s own error, as before. */
    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, -1"})
    void negativePageOrSizeBelowOne_isRefusedAsByPageRequestItself(int page, int size) {
        assertThatThrownBy(() -> PageRequests.of(page, size)).isInstanceOf(IllegalArgumentException.class);
    }
}
