package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ErrorPage}: the site's HTML error page says one fixed title and
 * one plain sentence per status — never anything taken from an exception.
 * Statuses without their own text fall back to a generic client-error or
 * server-error text.
 */
class ErrorPageTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "400 | Bad request | This request couldn't be understood. Check the link or the form and try again.",
        "403 | Access denied | This request was refused. Reload the page and try again.",
        "404 | Page not found | There's no page at this address. It may have been moved or removed.",
        "405 | Action not allowed | This page doesn't accept that kind of request.",
        "406 | Format not available | This page can't be sent in a format your browser accepts.",
        "415 | Unsupported format | This page can't read data sent in that format.",
        "500 | Something went wrong | An unexpected error occurred on our side. Please try again later."
    })
    void eachListedStatus_hasItsOwnFixedTitleAndSentence(int status, String title, String message) {
        ErrorPage page = ErrorPage.forStatus(status);

        assertThat(page.status()).isEqualTo(status);
        assertThat(page.title()).isEqualTo(title);
        assertThat(page.message()).isEqualTo(message);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 402, 409, 410, 413, 422, 429, 499})
    void anyOtherClientError_getsTheGenericClientErrorText(int status) {
        ErrorPage page = ErrorPage.forStatus(status);

        assertThat(page.status()).isEqualTo(status);
        assertThat(page.title()).isEqualTo("Request not completed");
        assertThat(page.message()).isEqualTo("This request couldn't be completed. Go back and try again.");
    }

    @ParameterizedTest
    @ValueSource(ints = {501, 502, 503, 504, 599})
    void anyOtherServerError_getsTheServerErrorText(int status) {
        ErrorPage page = ErrorPage.forStatus(status);

        assertThat(page.status()).isEqualTo(status);
        assertThat(page.title()).isEqualTo(ErrorPage.forStatus(500).title());
        assertThat(page.message()).isEqualTo(ErrorPage.forStatus(500).message());
    }

    /** Not an error status at all (a caller bug): never a blank page, always the server-error text. */
    @ParameterizedTest
    @ValueSource(ints = {0, -1, 100, 200, 302, 399, 600, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void aStatusThatIsNoError_stillGetsTheServerErrorText(int status) {
        ErrorPage page = ErrorPage.forStatus(status);

        assertThat(page.title()).isEqualTo(ErrorPage.forStatus(500).title());
        assertThat(page.message()).isEqualTo(ErrorPage.forStatus(500).message());
    }

    @Test
    void noText_isBlank_orNamesAnythingInternal() {
        IntStream.rangeClosed(400, 599).mapToObj(ErrorPage::forStatus).forEach(page -> {
            assertThat(page.title()).isNotBlank().doesNotContain("Exception", "java", "null");
            assertThat(page.message()).isNotBlank().doesNotContain("Exception", "java", "null").endsWith(".");
        });
    }
}
