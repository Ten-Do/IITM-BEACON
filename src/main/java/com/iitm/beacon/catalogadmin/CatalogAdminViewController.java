package com.iitm.beacon.catalogadmin;

import com.iitm.beacon.common.error.CatalogConflictException;
import com.iitm.beacon.common.error.CatalogValidationException;
import com.iitm.beacon.common.error.FieldViolation;
import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.domain.topic.Topic;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Thymeleaf view layer for the admin catalog (UC-MANAGE-TOPIC-GROUPS,
 * UC-MANAGE-TOPICS, UC-MANAGE-ACHIEVEMENTS, decision 28; routes in
 * docs/architecture.md §17): the two list pages, a form page per create and
 * edit, the one-click Active toggle, and the delete confirmation. Every rule
 * lives in {@link CatalogAdminService}; this controller only renders pages
 * and plain form posts.
 *
 * <p>Like {@code ModerationViewController}, it catches the service's
 * exceptions itself instead of letting {@code GlobalExceptionHandler} answer
 * the browser with the site's generic error page: a broken rule or a conflict
 * re-renders the form with the message next to its field (or above the form
 * when it concerns the change as a whole, e.g. the protected {@code general}
 * topic); an entry that no longer exists, or a toggle or delete the catalog
 * refuses, returns to the list with a flash message.
 */
@Controller
@RequestMapping("/catalog")
public class CatalogAdminViewController {

    private static final Logger log = LoggerFactory.getLogger(CatalogAdminViewController.class);

    /** A path id: digits only (at most 18, so it always fits a {@code Long}). */
    private static final String ID = "/{id:\\d{1,18}}";

    private static final String FORM = "form";
    private static final String NOTICE = "notice";
    private static final String ERROR = "error";

    private static final String TOPICS_LIST = "/catalog/topics";
    private static final String ACHIEVEMENTS_LIST = "/catalog/achievements";
    private static final String TOPIC_GROUPS_PATH = "/catalog/topic-groups";

    private static final String TOPICS_VIEW = "catalogadmin/topics";
    private static final String ACHIEVEMENTS_VIEW = "catalogadmin/achievements";
    private static final String TOPIC_GROUP_FORM_VIEW = "catalogadmin/topic-group-form";
    private static final String TOPIC_FORM_VIEW = "catalogadmin/topic-form";
    private static final String ACHIEVEMENT_FORM_VIEW = "catalogadmin/achievement-form";
    private static final String DELETE_CONFIRM_VIEW = "catalogadmin/delete-confirm";

    private static final String NAV_CATALOG = "catalog";
    private static final String NAV_ACHIEVEMENTS = "achievements";

    private static final String GROUP_GONE_MESSAGE = "That topic group no longer exists.";
    private static final String TOPIC_GONE_MESSAGE = "That topic no longer exists.";
    private static final String ACHIEVEMENT_GONE_MESSAGE = "That achievement no longer exists.";
    private static final String NO_STATE_MESSAGE = "Nothing changed: the toggle didn't say which state to set.";

    private final CatalogAdminService catalogAdminService;

    public CatalogAdminViewController(CatalogAdminService catalogAdminService) {
        this.catalogAdminService = catalogAdminService;
    }

    // -- list pages --

    @GetMapping("/topics")
    public String topics(Model model) {
        List<TopicGroupDto> groups = catalogAdminService.listTopicGroups();
        model.addAttribute("groups", groups);
        model.addAttribute("topics", catalogAdminService.listTopics());
        model.addAttribute("groupLabels", groupLabels(groups));
        model.addAttribute("inactiveGroupIds", groups.stream()
                .filter(group -> !group.active())
                .map(TopicGroupDto::id)
                .collect(Collectors.toSet()));
        return TOPICS_VIEW;
    }

    @GetMapping("/achievements")
    public String achievements(Model model) {
        model.addAttribute("achievements", catalogAdminService.listAchievements());
        return ACHIEVEMENTS_VIEW;
    }

    // -- topic groups --

    @GetMapping("/topic-groups/new")
    public String newTopicGroup(Model model) {
        model.addAttribute(FORM, new TopicGroupForm());
        return topicGroupForm(model, null);
    }

    @PostMapping("/topic-groups/new")
    public String createTopicGroup(
            @Valid @ModelAttribute(FORM) TopicGroupForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return topicGroupForm(model, null);
        }
        TopicGroupDto created = catalogAdminService.createTopicGroup(form.toCreateRequest());
        redirectAttributes.addFlashAttribute(NOTICE, "Topic group \"" + created.label() + "\" created.");
        return redirect(TOPICS_LIST);
    }

    @GetMapping("/topic-groups" + ID)
    public String editTopicGroup(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        Optional<TopicGroupDto> group = find(() -> catalogAdminService.getTopicGroup(id));
        if (group.isEmpty()) {
            return gone(redirectAttributes, GROUP_GONE_MESSAGE, TOPICS_LIST);
        }
        model.addAttribute(FORM, TopicGroupForm.of(group.get()));
        return topicGroupForm(model, id);
    }

    @PostMapping("/topic-groups" + ID)
    public String saveTopicGroup(
            @PathVariable Long id,
            @Valid @ModelAttribute(FORM) TopicGroupForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (find(() -> catalogAdminService.getTopicGroup(id)).isEmpty()) {
            return gone(redirectAttributes, GROUP_GONE_MESSAGE, TOPICS_LIST);
        }
        if (bindingResult.hasErrors()) {
            return topicGroupForm(model, id);
        }
        try {
            TopicGroupDto saved = catalogAdminService.patchTopicGroup(id, form.toPatchRequest());
            redirectAttributes.addFlashAttribute(NOTICE, "Topic group \"" + saved.label() + "\" saved.");
            return redirect(TOPICS_LIST);
        } catch (NotFoundException ex) {
            return gone(redirectAttributes, GROUP_GONE_MESSAGE, TOPICS_LIST);
        }
    }

    @PostMapping("/topic-groups" + ID + "/active")
    public String toggleTopicGroup(
            @PathVariable Long id,
            @RequestParam(required = false) String active,
            RedirectAttributes redirectAttributes) {
        return toggle(active, TOPICS_LIST, GROUP_GONE_MESSAGE, redirectAttributes, state -> {
            TopicGroupDto group =
                    catalogAdminService.patchTopicGroup(id, new TopicGroupPatchRequest(null, null, state));
            return "Topic group \"" + group.label() + "\"";
        });
    }

    @GetMapping("/topic-groups" + ID + "/delete")
    public String confirmDeleteTopicGroup(
            @PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return confirmDelete(model, redirectAttributes, () -> catalogAdminService.previewTopicGroupDelete(id),
                "topic-group", "topic group", TOPIC_GROUPS_PATH + "/" + id + "/delete", TOPICS_LIST,
                GROUP_GONE_MESSAGE, NAV_CATALOG);
    }

    @PostMapping("/topic-groups" + ID + "/delete")
    public String deleteTopicGroup(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        return delete(redirectAttributes, TOPICS_LIST, GROUP_GONE_MESSAGE, () -> {
            String label = catalogAdminService.getTopicGroup(id).label();
            catalogAdminService.deleteTopicGroup(id);
            return "Topic group \"" + label + "\" deleted, with its topics.";
        });
    }

    // -- topics --

    @GetMapping("/topics/new")
    public String newTopic(Model model) {
        model.addAttribute(FORM, new TopicForm());
        return topicForm(model, null, false);
    }

    @PostMapping("/topics/new")
    public String createTopic(
            @Valid @ModelAttribute(FORM) TopicForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return topicForm(model, null, false);
        }
        try {
            TopicDto created = catalogAdminService.createTopic(form.toCreateRequest());
            redirectAttributes.addFlashAttribute(NOTICE, "Topic \"" + created.label() + "\" created.");
            return redirect(TOPICS_LIST);
        } catch (CatalogValidationException ex) {
            addViolations(bindingResult, ex.getViolations());
        } catch (CatalogConflictException ex) {
            addConflict(bindingResult, ex);
        }
        return topicForm(model, null, false);
    }

    @GetMapping("/topics" + ID)
    public String editTopic(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        Optional<TopicDto> topic = find(() -> catalogAdminService.getTopic(id));
        if (topic.isEmpty()) {
            return gone(redirectAttributes, TOPIC_GONE_MESSAGE, TOPICS_LIST);
        }
        model.addAttribute(FORM, TopicForm.of(topic.get()));
        return topicForm(model, id, isGeneral(topic.get()));
    }

    @PostMapping("/topics" + ID)
    public String saveTopic(
            @PathVariable Long id,
            @Valid @ModelAttribute(FORM) TopicForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        Optional<TopicDto> current = find(() -> catalogAdminService.getTopic(id));
        if (current.isEmpty()) {
            return gone(redirectAttributes, TOPIC_GONE_MESSAGE, TOPICS_LIST);
        }
        boolean general = isGeneral(current.get());
        if (bindingResult.hasErrors()) {
            return topicForm(model, id, general);
        }
        try {
            TopicDto saved = catalogAdminService.patchTopic(id, form.toPatchRequest());
            redirectAttributes.addFlashAttribute(NOTICE, "Topic \"" + saved.label() + "\" saved.");
            return redirect(TOPICS_LIST);
        } catch (NotFoundException ex) {
            return gone(redirectAttributes, TOPIC_GONE_MESSAGE, TOPICS_LIST);
        } catch (CatalogValidationException ex) {
            addViolations(bindingResult, ex.getViolations());
        } catch (CatalogConflictException ex) {
            addConflict(bindingResult, ex);
        }
        return topicForm(model, id, general);
    }

    @PostMapping("/topics" + ID + "/active")
    public String toggleTopic(
            @PathVariable Long id,
            @RequestParam(required = false) String active,
            RedirectAttributes redirectAttributes) {
        return toggle(active, TOPICS_LIST, TOPIC_GONE_MESSAGE, redirectAttributes, state -> {
            TopicDto topic = catalogAdminService.patchTopic(
                    id, new TopicPatchRequest(null, null, null, null, state, null));
            return "Topic \"" + topic.label() + "\"";
        });
    }

    @GetMapping("/topics" + ID + "/delete")
    public String confirmDeleteTopic(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return confirmDelete(model, redirectAttributes, () -> catalogAdminService.previewTopicDelete(id),
                "topic", "topic", TOPICS_LIST + "/" + id + "/delete", TOPICS_LIST, TOPIC_GONE_MESSAGE, NAV_CATALOG);
    }

    @PostMapping("/topics" + ID + "/delete")
    public String deleteTopic(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        return delete(redirectAttributes, TOPICS_LIST, TOPIC_GONE_MESSAGE, () -> {
            String label = catalogAdminService.getTopic(id).label();
            catalogAdminService.deleteTopic(id);
            return "Topic \"" + label + "\" deleted.";
        });
    }

    // -- achievements --

    @GetMapping("/achievements/new")
    public String newAchievement(Model model) {
        model.addAttribute(FORM, new AchievementForm());
        return achievementForm(model, null);
    }

    @PostMapping("/achievements/new")
    public String createAchievement(
            @Valid @ModelAttribute(FORM) AchievementForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return achievementForm(model, null);
        }
        try {
            AchievementDto created = catalogAdminService.createAchievement(form.toCreateRequest());
            redirectAttributes.addFlashAttribute(NOTICE, "Achievement \"" + created.label() + "\" created.");
            return redirect(ACHIEVEMENTS_LIST);
        } catch (CatalogConflictException ex) {
            addConflict(bindingResult, ex);
        }
        return achievementForm(model, null);
    }

    @GetMapping("/achievements" + ID)
    public String editAchievement(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        Optional<AchievementDto> achievement = find(() -> catalogAdminService.getAchievement(id));
        if (achievement.isEmpty()) {
            return gone(redirectAttributes, ACHIEVEMENT_GONE_MESSAGE, ACHIEVEMENTS_LIST);
        }
        model.addAttribute(FORM, AchievementForm.of(achievement.get()));
        return achievementForm(model, id);
    }

    @PostMapping("/achievements" + ID)
    public String saveAchievement(
            @PathVariable Long id,
            @Valid @ModelAttribute(FORM) AchievementForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (find(() -> catalogAdminService.getAchievement(id)).isEmpty()) {
            return gone(redirectAttributes, ACHIEVEMENT_GONE_MESSAGE, ACHIEVEMENTS_LIST);
        }
        if (bindingResult.hasErrors()) {
            return achievementForm(model, id);
        }
        try {
            AchievementDto saved = catalogAdminService.patchAchievement(id, form.toPatchRequest());
            redirectAttributes.addFlashAttribute(NOTICE, "Achievement \"" + saved.label() + "\" saved.");
            return redirect(ACHIEVEMENTS_LIST);
        } catch (NotFoundException ex) {
            return gone(redirectAttributes, ACHIEVEMENT_GONE_MESSAGE, ACHIEVEMENTS_LIST);
        } catch (CatalogConflictException ex) {
            addConflict(bindingResult, ex);
        }
        return achievementForm(model, id);
    }

    @PostMapping("/achievements" + ID + "/active")
    public String toggleAchievement(
            @PathVariable Long id,
            @RequestParam(required = false) String active,
            RedirectAttributes redirectAttributes) {
        return toggle(active, ACHIEVEMENTS_LIST, ACHIEVEMENT_GONE_MESSAGE, redirectAttributes, state -> {
            AchievementDto achievement =
                    catalogAdminService.patchAchievement(id, new AchievementPatchRequest(null, null, null, state));
            return "Achievement \"" + achievement.label() + "\"";
        });
    }

    @GetMapping("/achievements" + ID + "/delete")
    public String confirmDeleteAchievement(
            @PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return confirmDelete(model, redirectAttributes, () -> catalogAdminService.previewAchievementDelete(id),
                "achievement", "achievement", ACHIEVEMENTS_LIST + "/" + id + "/delete", ACHIEVEMENTS_LIST,
                ACHIEVEMENT_GONE_MESSAGE, NAV_ACHIEVEMENTS);
    }

    @PostMapping("/achievements" + ID + "/delete")
    public String deleteAchievement(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        return delete(redirectAttributes, ACHIEVEMENTS_LIST, ACHIEVEMENT_GONE_MESSAGE, () -> {
            String label = catalogAdminService.getAchievement(id).label();
            catalogAdminService.deleteAchievement(id);
            return "Achievement \"" + label + "\" deleted.";
        });
    }

    // -- helpers --

    private String topicGroupForm(Model model, Long editingId) {
        model.addAttribute("editingId", editingId);
        return TOPIC_GROUP_FORM_VIEW;
    }

    private String topicForm(Model model, Long editingId, boolean general) {
        model.addAttribute("editingId", editingId);
        model.addAttribute("general", general);
        model.addAttribute("groups", catalogAdminService.listTopicGroups());
        return TOPIC_FORM_VIEW;
    }

    private String achievementForm(Model model, Long editingId) {
        model.addAttribute("editingId", editingId);
        return ACHIEVEMENT_FORM_VIEW;
    }

    /** Sets the requested state ("true"/"false" only); anything else changes nothing. */
    private String toggle(
            String active,
            String listPath,
            String goneMessage,
            RedirectAttributes redirectAttributes,
            StateChange change) {
        if (!"true".equals(active) && !"false".equals(active)) {
            redirectAttributes.addFlashAttribute(ERROR, NO_STATE_MESSAGE);
            return redirect(listPath);
        }
        boolean state = Boolean.parseBoolean(active);
        try {
            String entry = change.apply(state);
            redirectAttributes.addFlashAttribute(NOTICE, entry + " is now " + (state ? "active." : "inactive."));
        } catch (NotFoundException ex) {
            redirectAttributes.addFlashAttribute(ERROR, goneMessage);
        } catch (CatalogConflictException ex) {
            redirectAttributes.addFlashAttribute(ERROR, ex.getMessage());
        }
        return redirect(listPath);
    }

    private String confirmDelete(
            Model model,
            RedirectAttributes redirectAttributes,
            Supplier<DeletePreview> preview,
            String kind,
            String kindLabel,
            String deleteUrl,
            String listPath,
            String goneMessage,
            String navPage) {
        try {
            model.addAttribute("preview", preview.get());
        } catch (NotFoundException ex) {
            return gone(redirectAttributes, goneMessage, listPath);
        } catch (CatalogConflictException ex) {
            redirectAttributes.addFlashAttribute(ERROR, ex.getMessage());
            return redirect(listPath);
        }
        model.addAttribute("kind", kind);
        model.addAttribute("kindLabel", kindLabel);
        model.addAttribute("deleteUrl", deleteUrl);
        model.addAttribute("cancelUrl", listPath);
        model.addAttribute("navPage", navPage);
        return DELETE_CONFIRM_VIEW;
    }

    private String delete(
            RedirectAttributes redirectAttributes, String listPath, String goneMessage, Supplier<String> deletion) {
        try {
            redirectAttributes.addFlashAttribute(NOTICE, deletion.get());
        } catch (NotFoundException ex) {
            redirectAttributes.addFlashAttribute(ERROR, goneMessage);
        } catch (CatalogConflictException ex) {
            redirectAttributes.addFlashAttribute(ERROR, ex.getMessage());
        }
        return redirect(listPath);
    }

    /** The entry, or empty if it doesn't exist (any more). */
    private static <T> Optional<T> find(Supplier<T> lookup) {
        try {
            return Optional.of(lookup.get());
        } catch (NotFoundException ex) {
            return Optional.empty();
        }
    }

    private static String gone(RedirectAttributes redirectAttributes, String message, String listPath) {
        log.info("Catalog page: {}", message);
        redirectAttributes.addFlashAttribute(ERROR, message);
        return redirect(listPath);
    }

    /** Each violation next to its field; one without a field above the form. */
    private static void addViolations(BindingResult bindingResult, List<FieldViolation> violations) {
        for (FieldViolation violation : violations) {
            if (violation.isGlobal()) {
                bindingResult.reject("catalog", violation.message());
            } else {
                bindingResult.addError(new FieldError(
                        FORM, violation.field(), bindingResult.getRawFieldValue(violation.field()), false, null,
                        null, violation.message()));
            }
        }
    }

    private static void addConflict(BindingResult bindingResult, CatalogConflictException ex) {
        Optional<String> field = ex.getField();
        if (field.isPresent()) {
            bindingResult.addError(new FieldError(
                    FORM, field.get(), bindingResult.getRawFieldValue(field.get()), false, null, null,
                    ex.getMessage()));
        } else {
            bindingResult.reject("catalog", ex.getMessage());
        }
    }

    private static boolean isGeneral(TopicDto topic) {
        return Topic.GENERAL_SLUG.equals(topic.slug());
    }

    private static Map<Long, String> groupLabels(List<TopicGroupDto> groups) {
        Map<Long, String> labels = new LinkedHashMap<>();
        groups.forEach(group -> labels.put(group.id(), group.label()));
        return labels;
    }

    private static String redirect(String path) {
        return "redirect:" + path;
    }

    /** One toggle: sets the state and names the entry for the notice. */
    @FunctionalInterface
    private interface StateChange {
        String apply(boolean active);
    }
}
