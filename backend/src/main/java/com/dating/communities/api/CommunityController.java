package com.dating.communities.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.communities.application.CommunityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Interest groups (docs/communities.md): browsing, subscriptions, suggestions; staff edit them under /admin. */
@RestController
@RequestMapping("/api/v1")
public class CommunityController {

    public record SuggestionRequest(@NotBlank @Size(max = 80) String title, @Size(max = 300) String comment) {
    }

    public record CommunityRequest(String slug, @NotBlank String title, @NotBlank String kind, String emoji,
                                   String description, Integer sortOrder, Boolean active) {
        CommunityService.Change change() {
            return new CommunityService.Change(slug, title, kind, emoji, description, sortOrder, active);
        }
    }

    public record DecisionRequest(@NotNull Boolean accepted) {
    }

    private final CommunityService communities;

    public CommunityController(CommunityService communities) {
        this.communities = communities;
    }

    @GetMapping("/communities")
    public List<CommunityService.Community> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @RequestParam(name = "q", defaultValue = "") String query,
                                                 @RequestParam(required = false) String kind) {
        return communities.list(user.accountId(), query, kind);
    }

    @GetMapping("/communities/{id}")
    public CommunityService.Community get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return communities.get(user.accountId(), id);
    }

    @GetMapping("/me/communities")
    public List<CommunityService.Community> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return communities.mine(user.accountId());
    }

    @PutMapping("/communities/{id}/subscription")
    public CommunityService.Community subscribe(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return communities.subscribe(user.accountId(), id);
    }

    @DeleteMapping("/communities/{id}/subscription")
    public CommunityService.Community unsubscribe(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable long id) {
        return communities.unsubscribe(user.accountId(), id);
    }

    @PostMapping("/communities/suggestions")
    public ResponseEntity<Void> suggest(@AuthenticationPrincipal AuthenticatedUser user,
                                        @Valid @RequestBody SuggestionRequest request) {
        communities.suggest(user.accountId(), request.title(), request.comment());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ---------------------------------------------------------------- staff

    @GetMapping("/admin/communities")
    public List<CommunityService.AdminCommunity> all(@AuthenticationPrincipal AuthenticatedUser user) {
        return communities.all(user.accountId());
    }

    @PostMapping("/admin/communities")
    public ResponseEntity<CommunityService.AdminCommunity> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                                  @Valid @RequestBody CommunityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(communities.create(user.accountId(), request.change()));
    }

    @PutMapping("/admin/communities/{id}")
    public CommunityService.AdminCommunity update(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable long id, @Valid @RequestBody CommunityRequest request) {
        return communities.update(user.accountId(), id, request.change());
    }

    @GetMapping("/admin/communities/suggestions")
    public List<CommunityService.Suggestion> suggestions(@AuthenticationPrincipal AuthenticatedUser user) {
        return communities.suggestions(user.accountId());
    }

    @PostMapping("/admin/communities/suggestions/{suggestionId}/decision")
    public ResponseEntity<Void> decide(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID suggestionId, @Valid @RequestBody DecisionRequest request) {
        communities.decide(user.accountId(), suggestionId, request.accepted());
        return ResponseEntity.noContent().build();
    }
}
