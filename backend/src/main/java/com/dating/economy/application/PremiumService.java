package com.dating.economy.application;

import com.dating.common.entitlements.FeatureAccess;
import com.dating.common.errors.ApiException;
import com.dating.economy.persistence.EconomyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Premium is time-based: "premium until". Days come from any source (subscription, achievement,
 * gacha, promo, testing) through EconomyService.grant with Reward.PremiumDays. Expiry is checked on
 * read against the account's economy clock, so correctness does not depend on a scheduled job.
 */
@Service
public class PremiumService implements FeatureAccess {

    private final EconomyRepository repository;
    private final EconomyProperties properties;
    private final EconomyClock clock;

    public PremiumService(EconomyRepository repository, EconomyProperties properties, EconomyClock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Instant> premiumUntil(UUID accountId) {
        return repository.premiumUntil(accountId);
    }

    @Transactional(readOnly = true)
    public boolean isPremium(UUID accountId) {
        Instant now = clock.now(accountId);
        return premiumUntil(accountId).map(until -> until.isAfter(now)).orElse(false);
    }

    public List<String> premiumFeatures() {
        return properties.premiumFeatures();
    }

    @Override
    public boolean isAllowed(UUID accountId, String feature) {
        return !properties.premiumFeatures().contains(feature) || isPremium(accountId);
    }

    @Override
    public void require(UUID accountId, String feature) {
        if (!isAllowed(accountId, feature)) {
            throw ApiException.premiumRequired(feature);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<UUID> premiumAmong(java.util.Collection<UUID> accountIds) {
        return accountIds.isEmpty() ? java.util.Set.of() : repository.premiumAccounts(accountIds);
    }
}
