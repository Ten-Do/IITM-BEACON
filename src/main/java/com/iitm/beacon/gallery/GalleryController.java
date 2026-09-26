package com.iitm.beacon.gallery;

import com.iitm.beacon.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated read endpoints for the gallery slice
 * (UC-BROWSE-APPROVED, UC-FILTER-COUNTRY, UC-FILTER-TOPIC,
 * UC-SEARCH-KEYWORD, UC-EXPAND-TESTIMONIAL, UC-REVEAL-CONTACT). Role/method
 * authorization for this slice's routes is wired into {@code
 * config.SecurityConfig} in a later batch, alongside {@code moderation}.
 */
@RestController
@RequestMapping("/api/gallery")
@Validated
public class GalleryController {

    private final GalleryService galleryService;

    public GalleryController(GalleryService galleryService) {
        this.galleryService = galleryService;
    }

    @GetMapping("/testimonials")
    public PageResponse<TestimonialCardDto> browse(
            @RequestParam(required = false) @Size(min = 2, max = 2) String country,
            @RequestParam(required = false) List<Long> topicIds,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return galleryService.browse(country, topicIds, q, PageRequest.of(page, size));
    }

    @GetMapping("/testimonials/{id}")
    public TestimonialDetailDto detail(@PathVariable Long id) {
        return galleryService.getDetail(id);
    }

    @GetMapping("/testimonials/{id}/contact")
    public List<ContactMethodViewDto> contact(@PathVariable Long id) {
        return galleryService.revealContact(id);
    }

    @GetMapping("/countries")
    public List<CountryDto> countries() {
        return galleryService.listCountriesWithApproved();
    }

    @GetMapping("/topics")
    public List<TopicCatalogEntryDto> topics() {
        return galleryService.listTopicCatalogWithApproved();
    }
}
