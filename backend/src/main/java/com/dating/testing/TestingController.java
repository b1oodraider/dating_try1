package com.dating.testing;

import com.dating.accounts.application.AccountService;
import com.dating.achievements.application.AchievementService;
import com.dating.common.errors.ApiException;
import com.dating.common.security.AuthenticatedUser;
import com.dating.economy.application.EconomyClock;
import com.dating.economy.application.EconomyService;
import com.dating.economy.application.WalletQueryService;
import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * QA tools for test accounts (app.testing.enabled=true; the endpoints do not exist otherwise).
 * Only accounts flagged as test accounts may call them; every call returns the full test state.
 */
@RestController
@RequestMapping("/api/v1/testing")
@ConditionalOnProperty(prefix = "app.testing", name = "enabled", havingValue = "true")
public class TestingController {

    public record QuantityRequest(@Min(0) @Max(100_000) long quantity) {
    }

    public record DaysRequest(@Min(1) @Max(3650) int days) {
    }

    private final AccountService accountService;
    private final EconomyService economyService;
    private final EconomyClock clock;
    private final WalletQueryService walletQueryService;
    private final AchievementService achievementService;

    public TestingController(AccountService accountService, EconomyService economyService, EconomyClock clock,
                             WalletQueryService walletQueryService, AchievementService achievementService) {
        this.accountService = accountService;
        this.economyService = economyService;
        this.clock = clock;
        this.walletQueryService = walletQueryService;
        this.achievementService = achievementService;
    }

    @GetMapping("/state")
    public ResponseEntity<TestingStateDto> state(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(state(requireTestAccount(user)));
    }

    // ---------------------------------------------------------------- achievements

    @PostMapping("/achievements/{code}/complete")
    public ResponseEntity<TestingStateDto> completeAchievement(@AuthenticationPrincipal AuthenticatedUser user,
                                                               @PathVariable String code) {
        UUID accountId = requireTestAccount(user);
        achievementService.complete(accountId, code);
        return ResponseEntity.ok(state(accountId));
    }

    @DeleteMapping("/achievements/{code}")
    public ResponseEntity<TestingStateDto> resetAchievement(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @PathVariable String code) {
        UUID accountId = requireTestAccount(user);
        achievementService.resetForTesting(accountId, code);
        return ResponseEntity.ok(state(accountId));
    }

    // ---------------------------------------------------------------- wallet

    /** Adds units of a consumable (or grants a cosmetic) to the PERMANENT bucket. */
    @PostMapping("/wallet/items/{itemCode}")
    public ResponseEntity<TestingStateDto> grantItem(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @PathVariable String itemCode,
                                                     @Valid @RequestBody QuantityRequest request) {
        UUID accountId = requireTestAccount(user);
        if (request.quantity() < 1) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Количество должно быть больше нуля");
        }
        economyService.grant(accountId, RewardSource.TESTING, itemCode, "testing:" + UUID.randomUUID(),
                List.of(new Reward.Item(itemCode, request.quantity())));
        return ResponseEntity.ok(state(accountId));
    }

    /** Sets the PERMANENT balance exactly (0 removes; for cosmetics 0 or 1). */
    @PutMapping("/wallet/items/{itemCode}")
    public ResponseEntity<TestingStateDto> setItem(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @PathVariable String itemCode,
                                                   @Valid @RequestBody QuantityRequest request) {
        UUID accountId = requireTestAccount(user);
        economyService.setPermanentBalance(accountId, itemCode, request.quantity());
        return ResponseEntity.ok(state(accountId));
    }

    // ---------------------------------------------------------------- premium

    @PostMapping("/premium")
    public ResponseEntity<TestingStateDto> grantPremium(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @Valid @RequestBody DaysRequest request) {
        UUID accountId = requireTestAccount(user);
        economyService.grant(accountId, RewardSource.TESTING, "premium", "testing:" + UUID.randomUUID(),
                List.of(new Reward.PremiumDays(request.days())));
        return ResponseEntity.ok(state(accountId));
    }

    @DeleteMapping("/premium")
    public ResponseEntity<TestingStateDto> removePremium(@AuthenticationPrincipal AuthenticatedUser user) {
        UUID accountId = requireTestAccount(user);
        economyService.expirePremium(accountId);
        return ResponseEntity.ok(state(accountId));
    }

    // ---------------------------------------------------------------- economy clock

    /**
     * Moves this account's economy time forward, e.g. 7 days = "next premium week":
     * the next state shows refilled allowances and premium that may have expired.
     */
    @PostMapping("/clock/advance")
    public ResponseEntity<TestingStateDto> advanceClock(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @Valid @RequestBody DaysRequest request) {
        UUID accountId = requireTestAccount(user);
        clock.advance(accountId, Duration.ofDays(request.days()));
        return ResponseEntity.ok(state(accountId));
    }

    @DeleteMapping("/clock")
    public ResponseEntity<TestingStateDto> resetClock(@AuthenticationPrincipal AuthenticatedUser user) {
        UUID accountId = requireTestAccount(user);
        clock.reset(accountId);
        return ResponseEntity.ok(state(accountId));
    }

    private UUID requireTestAccount(AuthenticatedUser user) {
        if (!accountService.isTestAccount(user.accountId())) {
            throw ApiException.forbidden("TEST_ACCOUNT_REQUIRED", "Доступно только тестовым аккаунтам");
        }
        return user.accountId();
    }

    private TestingStateDto state(UUID accountId) {
        var wallet = walletQueryService.wallet(accountId);
        double offsetDays = clock.offset(accountId).toSeconds() / 86_400.0;
        return new TestingStateDto(accountId, offsetDays, clock.now(accountId), wallet,
                achievementService.list(accountId));
    }
}
