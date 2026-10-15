package com.dating.testing;

import com.dating.accounts.application.AccountService;
import com.dating.boost.api.BoostStatusDto;
import com.dating.boost.application.BoostService;
import com.dating.common.errors.ApiException;
import com.dating.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Boost tools for test accounts (app.testing.enabled=true). By default they act on the caller;
 * {@code ?accountId=} targets one of the seed profiles instead, so a single tester can watch a boosted
 * profile jump to the top of their own feed. Every call returns the target's boost state.
 */
@RestController
@RequestMapping("/api/v1/testing/boost")
@ConditionalOnProperty(prefix = "app.testing", name = "enabled", havingValue = "true")
public class TestBoostController {

    /** @param minutes boost length; default app.boost.duration */
    public record StartRequest(@Min(1) @Max(1440) Integer minutes) {
    }

    public record ImpressionsRequest(@Min(0) @Max(1_000_000_000) long impressions) {
    }

    /**
     * @param status same as GET /me/boost of the target account
     * @param boosts all boosts of the target (up to 100), newest first
     */
    public record TestBoostStateDto(UUID accountId, BoostStatusDto status, List<BoostStatusDto.BoostRecordDto> boosts) {
    }

    private final BoostService boostService;
    private final AccountService accountService;

    public TestBoostController(BoostService boostService, AccountService accountService) {
        this.boostService = boostService;
        this.accountService = accountService;
    }

    @GetMapping
    public TestBoostStateDto list(@AuthenticationPrincipal AuthenticatedUser user,
                                  @RequestParam(required = false) UUID accountId) {
        return state(target(user, accountId));
    }

    /** Starts a boost without spending BOOST; still needs a published profile and no active boost. */
    @PostMapping
    public TestBoostStateDto start(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestParam(required = false) UUID accountId,
                                   @Valid @RequestBody(required = false) StartRequest request) {
        UUID target = target(user, accountId);
        Duration duration = request == null || request.minutes() == null ? null : Duration.ofMinutes(request.minutes());
        boostService.startForTesting(target, duration);
        return state(target);
    }

    /** Ends the active boost now. */
    @DeleteMapping
    public TestBoostStateDto end(@AuthenticationPrincipal AuthenticatedUser user,
                                 @RequestParam(required = false) UUID accountId) {
        UUID target = target(user, accountId);
        boostService.endNow(target);
        return state(target);
    }

    /** Sets impressions of the active boost (or of the latest one), e.g. to check how stats look. */
    @PutMapping("/impressions")
    public TestBoostStateDto setImpressions(@AuthenticationPrincipal AuthenticatedUser user,
                                            @RequestParam(required = false) UUID accountId,
                                            @Valid @RequestBody ImpressionsRequest request) {
        UUID target = target(user, accountId);
        boostService.setImpressions(target, request.impressions());
        return state(target);
    }

    private UUID target(AuthenticatedUser user, UUID seedAccountId) {
        if (!accountService.isTestAccount(user.accountId())) {
            throw ApiException.forbidden("TEST_ACCOUNT_REQUIRED", "Доступно только тестовым аккаунтам");
        }
        if (seedAccountId == null || seedAccountId.equals(user.accountId())) {
            return user.accountId();
        }
        if (!accountService.isActiveSeedAccount(seedAccountId)) {
            throw ApiException.notFound("SEED_PROFILE_NOT_FOUND", "Тестовый профиль не найден");
        }
        return seedAccountId;
    }

    private TestBoostStateDto state(UUID accountId) {
        List<BoostStatusDto.BoostRecordDto> boosts = boostService.boostsOf(accountId).stream()
                .map(BoostStatusDto.BoostRecordDto::from)
                .toList();
        return new TestBoostStateDto(accountId, boostService.statusForTesting(accountId), boosts);
    }
}
