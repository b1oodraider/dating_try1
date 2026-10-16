package com.dating.economy.application;

import com.dating.economy.persistence.EconomyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Periodic consumables included in premium (app.economy.premium-allowances or the admin override,
 * see PremiumCompositionService). A changed allowance applies from the next period: the period key of an
 * already granted allowance is not granted again.
 * Granted lazily when the user opens the wallet and by a daily job; the period key makes both
 * paths idempotent, so a user gets each allowance at most once per period.
 */
@Service
public class AllowanceService {

    private static final Logger log = LoggerFactory.getLogger(AllowanceService.class);

    private final PremiumCompositionService composition;
    private final PremiumService premiumService;
    private final EconomyService economyService;
    private final EconomyRepository repository;
    private final EconomyClock clock;

    public AllowanceService(PremiumCompositionService composition, PremiumService premiumService,
                            EconomyService economyService, EconomyRepository repository, EconomyClock clock) {
        this.composition = composition;
        this.premiumService = premiumService;
        this.economyService = economyService;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Must be called outside of a transaction: each allowance is granted in its own transaction,
     * so a misconfigured item cannot break the others or the caller.
     */
    public void ensureAllowances(UUID accountId) {
        List<EconomyProperties.Allowance> allowances = composition.current().allowances();
        if (allowances.isEmpty() || !premiumService.isPremium(accountId)) {
            return;
        }
        Instant now = clock.now(accountId);
        for (EconomyProperties.Allowance allowance : allowances) {
            try {
                economyService.grantAllowance(accountId, allowance, allowance.period().keyFor(now));
            } catch (RuntimeException e) {
                log.error("Premium allowance {} was not granted: {}", allowance.itemCode(), e.getMessage());
            }
        }
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "UTC")
    public void grantToActivePremiumAccounts() {
        if (composition.current().allowances().isEmpty()) {
            return;
        }
        for (UUID accountId : repository.accountsWithActivePremium()) {
            ensureAllowances(accountId);
        }
    }
}
