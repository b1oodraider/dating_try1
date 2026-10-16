package com.dating.admin.api;

import com.dating.achievements.api.AchievementDto.RewardDto;
import com.dating.achievements.application.AchievementAdminService;
import com.dating.achievements.application.AchievementAdminService.Changes;
import com.dating.achievements.application.AchievementAdminService.Definition;
import com.dating.admin.application.AdminEconomyService;
import com.dating.admin.application.AdminEconomyService.PremiumView;
import com.dating.common.security.AuthenticatedUser;
import com.dating.economy.application.EconomyProperties;
import com.dating.economy.domain.AllowancePeriod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Achievement definitions (CONTENT_EDITOR, ADMIN) and the premium composition (ADMIN).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEconomyController {

    /**
     * Full replace of the editable fields; rewards use the same shape as GET /me/achievements.
     */
    public record AchievementRequest(
            @NotBlank(message = "Укажите название") @Size(max = 100) String title,
            @Size(max = 300) String description,
            @NotNull(message = "Укажите награды (можно пустой список)")
            @Size(max = AchievementAdminService.MAX_REWARDS) List<RewardDto> rewards,
            @NotNull(message = "Укажите, активно ли достижение") Boolean active) {
    }

    public record AllowanceRequest(
            @NotBlank(message = "Укажите расходник") String itemCode,
            @Min(value = 1, message = "Количество должно быть больше нуля") long quantity,
            @NotNull(message = "Период: DAILY, WEEKLY или MONTHLY") AllowancePeriod period,
            @NotNull(message = "Режим: TOP_UP или ADD") EconomyProperties.Mode mode) {
    }

    /**
     * @param features   premium-only feature codes; null = keep current
     * @param allowances periodic consumables of premium; null = keep current
     */
    public record PremiumRequest(List<String> features, List<@Valid AllowanceRequest> allowances) {
    }

    private final AdminEconomyService service;

    public AdminEconomyController(AdminEconomyService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- achievements

    @GetMapping("/achievements")
    public List<Definition> achievements(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.achievements(user.accountId());
    }

    @PutMapping("/achievements/{code}")
    public Definition updateAchievement(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                                        @Valid @RequestBody AchievementRequest request) {
        String description = request.description() == null || request.description().isBlank()
                ? null : request.description().strip();
        return service.updateAchievement(user.accountId(), code,
                new Changes(request.title().strip(), description, request.rewards(), request.active()));
    }

    // ---------------------------------------------------------------- premium

    @GetMapping("/premium")
    public PremiumView premium(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.premium(user.accountId());
    }

    @PutMapping("/premium")
    public PremiumView updatePremium(@AuthenticationPrincipal AuthenticatedUser user,
                                     @Valid @RequestBody PremiumRequest request) {
        List<EconomyProperties.Allowance> allowances = request.allowances() == null ? null
                : request.allowances().stream()
                .map(a -> new EconomyProperties.Allowance(a.itemCode().strip(), a.quantity(), a.period(), a.mode()))
                .toList();
        return service.updatePremium(user.accountId(), request.features(), allowances);
    }

    /**
     * Back to app.economy.* from config for both lists.
     */
    @DeleteMapping("/premium")
    public PremiumView resetPremium(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.resetPremium(user.accountId());
    }
}
