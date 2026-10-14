package com.dating.economy.application;

import com.dating.economy.persistence.EconomyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Periodic consumables included in premium (configured in app.economy.premium-allowances).
 * Granted lazily when the user opens the wallet and by a daily job; the period key makes both
 * paths idempotent, so a user gets each allowance at most once per period.
 */
@Service
public class AllowanceService {

    private static final Logger log = LoggerFactory.getLogger(AllowanceService.class);

    private final EconomyProperties properties;
    private final PremiumService premiumService;
    private final EconomyService economyService;
    private final EconomyRepository repository;

    public AllowanceService(EconomyProperties properties, PremiumService premiumService,
                            EconomyService economyService, EconomyRepository repository) {
        this.properties = properties;
        this.premiumService = premiumService;
        this.economyService = economyService;
        this.repository = repository;
    }

    /**
     * Must be called outside of a transaction: each allowance is granted in its own transaction,
     * so a misconfigured item cannot break the others or the caller.
     */
    public void ensureAllowances(UUID accountId) {
        if (properties.premiumAllowances().isEmpty() || !premiumService.isPremium(accountId)) {
            return;
        }
        Instant now = Instant.now();
        for (EconomyProperties.Allowance allowance : properties.premiumAllowances()) {
            try {
                economyService.grantAllowance(accountId, allowance, allowance.period().keyFor(now));
            } catch (RuntimeException e) {
                log.error("Premium allowance {} was not granted: {}", allowance.itemCode(), e.getMessage());
            }
        }
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "UTC")
    public void grantToActivePremiumAccounts() {
        if (properties.premiumAllowances().isEmpty()) {
            return;
        }
        for (UUID accountId : repository.accountsWithActivePremium()) {
            ensureAllowances(accountId);
        }
    }
}
