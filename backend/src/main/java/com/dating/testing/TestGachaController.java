package com.dating.testing;

import com.dating.accounts.application.AccountService;
import com.dating.common.errors.ApiException;
import com.dating.common.security.AuthenticatedUser;
import com.dating.gacha.api.GachaTestingStateDto;
import com.dating.gacha.application.GachaTestingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Gacha QA tools for test accounts (app.testing.enabled=true; the endpoints do not exist otherwise).
 * Tickets are granted with the wallet tool: POST /api/v1/testing/wallet/items/GACHA_TICKET {"quantity": n}.
 */
@RestController
@RequestMapping("/api/v1/testing/gacha")
@ConditionalOnProperty(prefix = "app.testing", name = "enabled", havingValue = "true")
public class TestGachaController {

    public record ForcedRequest(@NotNull(message = "Укажите позиции") List<String> entryIds) {
    }

    public record PityRequest(
            @Min(value = 0, message = "От 0 до 10000") @Max(value = 10_000, message = "От 0 до 10000") int pullsSinceEpic,
            @Min(value = 0, message = "От 0 до 10000") @Max(value = 10_000, message = "От 0 до 10000") int pullsSinceLegendary) {
    }

    private final GachaTestingService gachaTesting;
    private final AccountService accountService;

    public TestGachaController(GachaTestingService gachaTesting, AccountService accountService) {
        this.gachaTesting = gachaTesting;
        this.accountService = accountService;
    }

    @GetMapping("/state")
    public GachaTestingStateDto state(@AuthenticationPrincipal AuthenticatedUser user) {
        return gachaTesting.state(requireTestAccount(user));
    }

    /** Replaces the forced queue of the banner: the next pulls there return these entries in order. */
    @PutMapping("/banners/{code}/forced")
    public GachaTestingStateDto force(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                                      @Valid @RequestBody ForcedRequest request) {
        return gachaTesting.forceNext(requireTestAccount(user), code, request.entryIds());
    }

    /** Clears the forced queues of all banners. */
    @DeleteMapping("/forced")
    public GachaTestingStateDto clearForced(@AuthenticationPrincipal AuthenticatedUser user) {
        return gachaTesting.clearForced(requireTestAccount(user));
    }

    @PutMapping("/pity/{pityGroup}")
    public GachaTestingStateDto setPity(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String pityGroup,
                                        @Valid @RequestBody PityRequest request) {
        return gachaTesting.setPity(requireTestAccount(user), pityGroup, request.pullsSinceEpic(),
                request.pullsSinceLegendary());
    }

    /** Resets the counters of all pity groups to zero. */
    @DeleteMapping("/pity")
    public GachaTestingStateDto resetPity(@AuthenticationPrincipal AuthenticatedUser user) {
        return gachaTesting.resetPity(requireTestAccount(user));
    }

    /** Deletes the pull history (wallet and pity counters are kept). */
    @DeleteMapping("/history")
    public GachaTestingStateDto clearHistory(@AuthenticationPrincipal AuthenticatedUser user) {
        return gachaTesting.clearHistory(requireTestAccount(user));
    }

    private UUID requireTestAccount(AuthenticatedUser user) {
        if (!accountService.isTestAccount(user.accountId())) {
            throw ApiException.forbidden("TEST_ACCOUNT_REQUIRED", "Доступно только тестовым аккаунтам");
        }
        return user.accountId();
    }
}
