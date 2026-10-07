package com.iitm.beacon.catalogadmin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin catalog endpoints (UC-MANAGE-TOPIC-GROUPS, UC-MANAGE-TOPICS,
 * UC-MANAGE-ACHIEVEMENTS, decision 28), {@code /api/catalog/**} in
 * api-spec.yaml. Admin-only through {@code config.SecurityConfig}; every
 * rule lives in {@link CatalogAdminService}, and its exceptions become 400,
 * 404 and 409 in {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogAdminController {

    private final CatalogAdminService catalogAdminService;

    public CatalogAdminController(CatalogAdminService catalogAdminService) {
        this.catalogAdminService = catalogAdminService;
    }

    // -- topic groups --

    @GetMapping("/topic-groups")
    public List<TopicGroupDto> listTopicGroups() {
        return catalogAdminService.listTopicGroups();
    }

    @PostMapping("/topic-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public TopicGroupDto createTopicGroup(@Valid @RequestBody TopicGroupCreateRequest request) {
        return catalogAdminService.createTopicGroup(request);
    }

    @PatchMapping("/topic-groups/{id}")
    public TopicGroupDto patchTopicGroup(@PathVariable Long id, @Valid @RequestBody TopicGroupPatchRequest request) {
        return catalogAdminService.patchTopicGroup(id, request);
    }

    @DeleteMapping("/topic-groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTopicGroup(@PathVariable Long id) {
        catalogAdminService.deleteTopicGroup(id);
    }

    // -- topics --

    @GetMapping("/topics")
    public List<TopicDto> listTopics() {
        return catalogAdminService.listTopics();
    }

    @PostMapping("/topics")
    @ResponseStatus(HttpStatus.CREATED)
    public TopicDto createTopic(@Valid @RequestBody TopicCreateRequest request) {
        return catalogAdminService.createTopic(request);
    }

    @PatchMapping("/topics/{id}")
    public TopicDto patchTopic(@PathVariable Long id, @Valid @RequestBody TopicPatchRequest request) {
        return catalogAdminService.patchTopic(id, request);
    }

    @DeleteMapping("/topics/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTopic(@PathVariable Long id) {
        catalogAdminService.deleteTopic(id);
    }

    // -- achievements --

    @GetMapping("/achievements")
    public List<AchievementDto> listAchievements() {
        return catalogAdminService.listAchievements();
    }

    @PostMapping("/achievements")
    @ResponseStatus(HttpStatus.CREATED)
    public AchievementDto createAchievement(@Valid @RequestBody AchievementCreateRequest request) {
        return catalogAdminService.createAchievement(request);
    }

    @PatchMapping("/achievements/{id}")
    public AchievementDto patchAchievement(
            @PathVariable Long id, @Valid @RequestBody AchievementPatchRequest request) {
        return catalogAdminService.patchAchievement(id, request);
    }

    @DeleteMapping("/achievements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAchievement(@PathVariable Long id) {
        catalogAdminService.deleteAchievement(id);
    }
}
