package com.dating.discovery.application;

import com.dating.common.entitlements.FeatureAccess;
import com.dating.common.entitlements.PremiumFeatures;
import com.dating.common.errors.ApiException;
import com.dating.discovery.domain.DiscoveryPreferences;
import com.dating.discovery.persistence.DiscoveryPreferencesRepository;
import com.dating.profiles.domain.RelationshipGoal;
import com.dating.profiles.persistence.RelationshipGoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Premium hard filter: profiles that have any of the excluded goals are hidden from the owner's feed.
 * The list is kept when premium ends but is not applied until premium is active again.
 */
@Service
public class ExcludedGoalsService {

    private final DiscoveryPreferencesRepository repository;
    private final RelationshipGoalRepository goalRepository;
    private final FeatureAccess featureAccess;

    public ExcludedGoalsService(DiscoveryPreferencesRepository repository, RelationshipGoalRepository goalRepository,
                                FeatureAccess featureAccess) {
        this.repository = repository;
        this.goalRepository = goalRepository;
        this.featureAccess = featureAccess;
    }

    @Transactional(readOnly = true)
    public Set<String> goalsOf(UUID accountId) {
        return repository.findById(accountId).map(DiscoveryPreferences::getExcludedGoals).orElse(Set.of());
    }

    /**
     * Goals to apply in discovery: empty when the feature is not available to the account.
     */
    @Transactional(readOnly = true)
    public Set<String> activeGoalsOf(UUID accountId) {
        return featureAccess.isAllowed(accountId, PremiumFeatures.ADVANCED_FILTERS) ? goalsOf(accountId) : Set.of();
    }

    public boolean isActive(UUID accountId) {
        return featureAccess.isAllowed(accountId, PremiumFeatures.ADVANCED_FILTERS);
    }

    @Transactional
    public Set<String> replace(UUID accountId, List<String> rawCodes) {
        featureAccess.require(accountId, PremiumFeatures.ADVANCED_FILTERS);
        Set<String> codes = rawCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!codes.isEmpty()) {
            Set<String> known = goalRepository.findAllByCodeInAndActiveTrue(codes).stream()
                    .map(RelationshipGoal::getCode).collect(Collectors.toSet());
            List<String> unknown = codes.stream().filter(code -> !known.contains(code)).toList();
            if (!unknown.isEmpty()) {
                throw ApiException.validation("UNKNOWN_GOAL", "Ошибка валидации входных данных",
                        Map.of("goalCodes", "Неизвестные цели: " + String.join(", ", unknown)));
            }
        }
        DiscoveryPreferences preferences = repository.findById(accountId).orElseGet(() -> new DiscoveryPreferences(accountId));
        preferences.replaceExcludedGoals(codes);
        repository.save(preferences);
        return Set.copyOf(codes);
    }
}
