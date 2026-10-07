package com.iitm.beacon.gallery;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.score.RecommendationScoreLabels;
import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.common.web.SameOriginOnly;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Thymeleaf view layer for the public {@code gallery} slice
 * (UC-BROWSE-APPROVED, UC-FILTER-COUNTRY, UC-FILTER-TOPIC,
 * UC-SEARCH-KEYWORD, UC-EXPAND-TESTIMONIAL, UC-REVEAL-CONTACT,
 * UC-VIEW-PHOTOS-FULLSCREEN). Delegates all business logic to {@link
 * GalleryService} — the JSON API in {@link GalleryController} covers the
 * browse/expand use cases for API consumers; this controller renders the
 * plain-HTML browse/filter/expand flow, plus the article's contact reveal.
 *
 * <p>Contact reveal (UC-REVEAL-CONTACT) is a {@code POST} to {@value
 * #CONTACT_PATH}, answered only for this site's own pages ({@link
 * SameOriginOnly}; there is no other way to read a contact). The article's
 * script ({@code static/js/contact-reveal.js}) sends it with {@value
 * #FRAGMENT_HEADER}{@code : }{@value #FRAGMENT_HEADER_VALUE} and gets back
 * the contact card alone, to swap in place; a plain form submission (no
 * JavaScript) gets the whole article with the card in place of the button.
 * Both render the same {@code gallery/contact-card.html} fragments.
 *
 * <p>The handlers catch {@link NotFoundException} themselves (same
 * convention as the sibling view controllers) rather than letting it reach
 * {@code GlobalExceptionHandler} (a {@code @RestControllerAdvice} that would
 * write a JSON body — the wrong response shape for a browser page) and
 * render a 404 page or fragment instead.
 */
@Controller
public class GalleryViewController {

    private static final Logger log = LoggerFactory.getLogger(GalleryViewController.class);

    private static final int PAGE_SIZE = 20;
    private static final String LIST_VIEW = "gallery/list";
    private static final String DETAIL_VIEW = "gallery/detail";
    private static final String NOT_FOUND_VIEW = "gallery/not-found";
    private static final String CONTACT_CARD_FRAGMENT = "gallery/contact-card :: card";
    private static final String CONTACT_UNAVAILABLE_FRAGMENT = "gallery/contact-card :: unavailable";

    /** Only an id of up to 18 digits (always a {@code Long}): anything else is simply no such page. */
    static final String CONTACT_PATH = "/gallery/{id:\\d{1,18}}/contact";

    /** The request header, and its value, by which the article's script asks for the contact card alone. */
    static final String FRAGMENT_HEADER = "X-Requested-With";
    static final String FRAGMENT_HEADER_VALUE = "fetch";

    private final GalleryService galleryService;

    public GalleryViewController(GalleryService galleryService) {
        this.galleryService = galleryService;
    }

    @GetMapping("/gallery")
    public String list(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) List<Long> groupIds,
            @RequestParam(required = false) List<Long> topicIds,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        int safePage = Math.max(page, 0);
        List<Long> safeGroupIds = groupIds == null ? List.of() : groupIds;
        List<Long> safeTopicIds = topicIds == null ? List.of() : topicIds;
        PageResponse<TestimonialCardDto> results = galleryService.browse(
                country, safeGroupIds, safeTopicIds, q, PageRequest.of(safePage, PAGE_SIZE));

        model.addAttribute("results", results);
        model.addAttribute("countries", galleryService.listCountriesWithApproved());
        model.addAttribute("topics", galleryService.listTopicCatalogWithApproved());
        model.addAttribute("country", country);
        model.addAttribute("groupIds", safeGroupIds);
        model.addAttribute("topicIds", safeTopicIds);
        model.addAttribute("q", q);
        return LIST_VIEW;
    }

    @GetMapping("/gallery/{id}")
    public String detail(@PathVariable Long id, Model model, HttpServletResponse response) {
        if (!addArticle(id, model)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return NOT_FOUND_VIEW;
        }
        return DETAIL_VIEW;
    }

    /**
     * The article's script asking for the contact card: just the card, or a
     * 404 with the "unavailable" card for an unknown or unapproved
     * testimonial, or one without a public contact.
     */
    @SameOriginOnly
    @PostMapping(path = CONTACT_PATH, headers = FRAGMENT_HEADER + "=" + FRAGMENT_HEADER_VALUE)
    public String contactCard(@PathVariable Long id, Model model, HttpServletResponse response) {
        Optional<List<ContactMethodViewDto>> contacts = publicContacts(id);
        if (contacts.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return CONTACT_UNAVAILABLE_FRAGMENT;
        }
        model.addAttribute("contacts", contacts.get());
        return CONTACT_CARD_FRAGMENT;
    }

    /**
     * The reveal button's form submitted without JavaScript: the whole
     * article, with the contact card in place of the button — or, with no
     * public contact to show, the "unavailable" card and a 404.
     */
    @SameOriginOnly
    @PostMapping(CONTACT_PATH)
    public String detailWithContact(@PathVariable Long id, Model model, HttpServletResponse response) {
        if (!addArticle(id, model)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return NOT_FOUND_VIEW;
        }
        Optional<List<ContactMethodViewDto>> contacts = publicContacts(id);
        if (contacts.isPresent()) {
            model.addAttribute("contacts", contacts.get());
        } else {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("contactUnavailable", true);
        }
        return DETAIL_VIEW;
    }

    /** Puts the approved testimonial's article into the model; false if there is none to show. */
    private boolean addArticle(Long id, Model model) {
        TestimonialDetailDto detail;
        try {
            detail = galleryService.getDetail(id);
        } catch (NotFoundException ex) {
            return false;
        }
        model.addAttribute("testimonial", detail);
        model.addAttribute("scoreLabel", RecommendationScoreLabels.forScore(detail.recommendationScore()));
        model.addAttribute("sectionBlocks", buildSectionBlocks(detail.sections()));
        model.addAttribute(
                "achievementLabels",
                detail.achievements().stream().map(this::humanize).toList());
        return true;
    }

    private Optional<List<ContactMethodViewDto>> publicContacts(Long id) {
        try {
            return Optional.of(galleryService.revealContact(id));
        } catch (NotFoundException ex) {
            log.info("Reveal-contact skipped for testimonial {}: {}", id, ex.getMessage());
            return Optional.empty();
        }
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
