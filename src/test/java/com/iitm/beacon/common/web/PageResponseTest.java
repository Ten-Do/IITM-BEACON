package com.iitm.beacon.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    void of_buildsResponseFromGivenContentAndSpringPageMetadata() {
        // The mapped DTO content ("a", "b") is deliberately a different shape
        // than the entity Page<Integer> it's paired with — of(...) must read
        // page/size/totalElements from the Spring Page, never re-derive them
        // from the content list.
        PageImpl<Integer> springPage = new PageImpl<>(List.of(1, 2), PageRequest.of(0, 5), 12);

        PageResponse<String> response = PageResponse.of(List.of("a", "b"), springPage);

        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(12);
    }

    @Test
    void of_emptyContent_returnsZeroTotalElementsAndEmptyContentList() {
        PageImpl<Integer> emptySpringPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);

        PageResponse<String> response = PageResponse.of(List.of(), emptySpringPage);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    void of_lastPageRequestedBeyondTotalPages_stillReportsRequestedPageNumber() {
        // A caller may request a page index past the last real page (e.g. UI
        // pagination boundary) — of(...) must faithfully report whatever
        // Pageable the Spring Page carries, not clamp it.
        PageImpl<Integer> springPage = new PageImpl<>(List.of(), PageRequest.of(3, 5), 12);

        PageResponse<String> response = PageResponse.of(List.of(), springPage);

        assertThat(response.page()).isEqualTo(3);
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(12);
    }
}
