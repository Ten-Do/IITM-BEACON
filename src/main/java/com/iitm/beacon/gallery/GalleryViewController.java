package com.iitm.beacon.gallery;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.web.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Thymeleaf view layer for the public {@code gallery} slice
 * (UC-BROWSE-APPROVED, UC-FILTER-COUNTRY, UC-FILTER-TOPIC,
 * UC-SEARCH-KEYWORD, UC-EXPAND-TESTIMONIAL, UC-REVEAL-CONTACT,
 * UC-VIEW-PHOTOS-FULLSCREEN). Delegates all business logic to {@link
 * GalleryService} — the JSON API in {@link GalleryController} already covers
 * the same use cases for API consumers; this controller only renders/drives
 * the plain-HTML browse/filter/expand/reveal flow (no SPA, no XHR, per the
 * project's established "simplest approach" convention — see {@code
 * moderation.ModerationViewController}/{@code submission.SubmissionViewController}).
 *
 * <p>{@link #detail(Long, String, Model, HttpServletResponse)} catches
 * {@link NotFoundException} itself (same convention as the sibling view
 * controllers) rather than letting it reach {@code GlobalExceptionHandler} (a
 * {@code @RestControllerAdvice} that would write a JSON body — the wrong
 * response shape for a browser page navigation) and instead renders a small
 * in-slice 404 page with the response status set accordingly.
 */
@Controller
public class GalleryViewController {

    private static final Logger log = LoggerFactory.getLogger(GalleryViewController.class);

    private static final int PAGE_SIZE = 20;
    private static final String LIST_VIEW = "gallery/list";
    private static final String DETAIL_VIEW = "gallery/detail";
    private static final String NOT_FOUND_VIEW = "gallery/not-found";
    private static final String REVEAL_TRUE = "true";

    /**
     * Fixed score → label table (docs/use-cases.md "Recommendation score"),
     * indexed directly by the 0-10 score — every {@code
     * TestimonialDetailDto.recommendationScore()} is guaranteed in range by
     * the {@code testimonial.recommendation_score} DB check constraint.
     */
    private static final List<String> SCORE_LABELS = List.of(
            "Terrible — I regretted my choice a hundred times over",
            "A very rough experience, almost nothing positive",
            "It was very hard",
            "Lots of serious problems",
            "More disappointed than satisfied",
            "Mixed — real upsides, but real downsides too",
            "Solid overall, would work for a lot of people",
            "A good experience, happy with the choice",
            "A great experience, a lot to remember",
            "Excellent! I'm taking a wealth of memories with me!",
            "Unforgettable! One of the best decisions of this year!");

    private final GalleryService galleryService;

    public GalleryViewController(GalleryService galleryService) {
        this.galleryService = galleryService;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/gallery";
    }

    @GetMapping("/gallery")
    public String list(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) List<Long> topicIds,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        int safePage = Math.max(page, 0);
        List<Long> safeTopicIds = topicIds == null ? List.of() : topicIds;
        PageResponse<TestimonialCardDto> results =
                galleryService.browse(country, safeTopicIds, q, PageRequest.of(safePage, PAGE_SIZE));

        model.addAttribute("results", results);
        model.addAttribute("countries", galleryService.listCountriesWithApproved());
        model.addAttribute("topics", galleryService.listTopicCatalogWithApproved());
        model.addAttribute("country", country);
        model.addAttribute("topicIds", safeTopicIds);
        model.addAttribute("q", q);
        return LIST_VIEW;
    }

    @GetMapping("/gallery/{id}")
    public String detail(
            @PathVariable Long id,
            @RequestParam(required = false) String reveal,
            Model model,
            HttpServletResponse response) {
        TestimonialDetailDto detail;
        try {
            detail = galleryService.getDetail(id);
        } catch (NotFoundException ex) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return NOT_FOUND_VIEW;
        }

        model.addAttribute("testimonial", detail);
        model.addAttribute("scoreLabel", SCORE_LABELS.get(detail.recommendationScore()));
        model.addAttribute("sectionBlocks", buildSectionBlocks(detail.sections()));
        model.addAttribute(
                "achievementLabels",
                detail.achievements().stream().map(this::humanize).toList());

        if (REVEAL_TRUE.equals(reveal)) {
            try {
                model.addAttribute("contacts", galleryService.revealContact(id));
            } catch (NotFoundException ex) {
                log.info("Reveal-contact skipped for testimonial {}: {}", id, ex.getMessage());
            }
        }
        return DETAIL_VIEW;
    }

    /**
     * Re-nests the already-ordered flat {@code sections} list into
     * group/standalone blocks for the article view (UC-EXPAND-TESTIMONIAL):
     * consecutive sections sharing the same topic-group are folded into one
     * {@link SectionBlock}, a standalone topic gets its own single-section
     * block. Group membership is looked up from {@link
     * GalleryService#listTopicCatalogWithApproved()} (a Service-layer call,
     * not a direct repository access) rather than re-deriving it from {@code
     * TestimonialSectionViewDto} itself, which deliberately carries no
     * group-membership field.
     */
    private List<SectionBlock> buildSectionBlocks(List<TestimonialSectionViewDto> sections) {
        Map<String, String> groupLabelByTopicSlug = new HashMap<>();
        for (TopicCatalogEntryDto entry : galleryService.listTopicCatalogWithApproved()) {
            if ("GROUP".equals(entry.kind())) {
                for (TopicPickDto pick : entry.subtopics()) {
                    groupLabelByTopicSlug.put(pick.slug(), entry.label());
                }
            }
        }

        List<SectionBlock> blocks = new ArrayList<>();
        String openGroupLabel = null;
        List<TestimonialSectionViewDto> openGroupSections = null;
        for (TestimonialSectionViewDto section : sections) {
            String groupLabel = groupLabelByTopicSlug.get(section.topicSlug());
            if (groupLabel == null) {
                blocks.add(new SectionBlock(section.topicLabel(), false, List.of(section)));
                openGroupLabel = null;
                openGroupSections = null;
            } else if (groupLabel.equals(openGroupLabel)) {
                openGroupSections.add(section);
            } else {
                openGroupSections = new ArrayList<>();
                openGroupSections.add(section);
                blocks.add(new SectionBlock(groupLabel, true, openGroupSections));
                openGroupLabel = groupLabel;
            }
        }
        return blocks;
    }

    /** "made_new_friends" -> "Made new friends" — a display-only transform, not a real label lookup. */
    private String humanize(String slug) {
        if (slug == null || slug.isBlank()) {
            return slug;
        }
        String withSpaces = slug.replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(withSpaces.charAt(0)) + withSpaces.substring(1);
    }

    /** One rendered topic-card: a topic-group with its filled subtopics, or one standalone topic. */
    public record SectionBlock(String heading, boolean group, List<TestimonialSectionViewDto> sections) {
    }
}
