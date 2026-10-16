package com.dating.safety.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.safety.application.BehaviorService;
import com.dating.safety.persistence.BehaviorRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * How long profiles were looked at, sent by the client in batches (when a card leaves the screen or the details
 * are closed). Tells "liked without looking" apart from a quick decision; never shown to anybody.
 */
@RestController
public class ProfileViewsController {

    public record ViewsRequest(
            @NotNull(message = "Укажите просмотры") @Size(min = 1, max = 100, message = "От 1 до 100 просмотров")
            List<@Valid ViewItem> views
    ) {
    }

    /** @param dwellMs time on screen, card and details together */
    public record ViewItem(
            @NotNull(message = "Укажите анкету") UUID profileId,
            @NotNull(message = "Укажите время просмотра") @Min(value = 0, message = "Не меньше 0")
            @Max(value = 3600000, message = "Не больше часа") Integer dwellMs,
            Boolean openedDetails
    ) {
    }

    private final BehaviorService behaviorService;

    public ProfileViewsController(BehaviorService behaviorService) {
        this.behaviorService = behaviorService;
    }

    @PostMapping("/api/v1/discovery/views")
    public ResponseEntity<Void> record(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody ViewsRequest request) {
        behaviorService.recordViews(user.accountId(), request.views().stream()
                .map(v -> new BehaviorRepository.View(v.profileId(), v.dwellMs(), Boolean.TRUE.equals(v.openedDetails())))
                .toList());
        return ResponseEntity.noContent().build();
    }
}
