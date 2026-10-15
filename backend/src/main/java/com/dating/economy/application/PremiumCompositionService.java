package com.dating.economy.application;

import com.dating.common.errors.ApiException;
import com.dating.economy.application.EconomyProperties.Allowance;
import com.dating.economy.domain.CatalogItem;
import com.dating.economy.domain.ItemKind;
import com.dating.economy.persistence.EconomyRepository;
import com.dating.economy.persistence.PremiumSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What premium includes right now: premium-only feature codes and periodic allowances.
 * <p>
 * Config ({@code app.economy.premium-features}, {@code app.economy.premium-allowances}) is the default;
 * an admin can override each list in the DB (premium_settings) and reset it back to the config.
 * So a fresh environment, the test profile and existing deployments behave exactly as configured until
 * someone edits the composition.
 * <p>
 * Read on every premium check, so it is cached in memory: dropped after a local write commits, and
 * reloaded at least every {@link #CACHE_TTL} so other instances pick up an edit made elsewhere.
 */
@Service
public class PremiumCompositionService {

    private static final Logger log = LoggerFactory.getLogger(PremiumCompositionService.class);

    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final Pattern FEATURE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,49}$");
    private static final int MAX_FEATURES = 50;
    private static final int MAX_ALLOWANCES = 20;
    private static final long MAX_ALLOWANCE_QUANTITY = 1000;
    private static final TypeReference<List<String>> FEATURES = new TypeReference<>() {
    };
    private static final TypeReference<List<Allowance>> ALLOWANCES = new TypeReference<>() {
    };

    /**
     * @param featuresOverridden   true if features come from the DB, false if from config
     * @param allowancesOverridden true if allowances come from the DB, false if from config
     */
    public record Composition(List<String> features, List<Allowance> allowances,
                              boolean featuresOverridden, boolean allowancesOverridden) {
    }

    private record Cached(Composition composition, Instant loadedAt) {
    }

    private final EconomyProperties defaults;
    private final PremiumSettingsRepository settings;
    private final EconomyRepository economyRepository;
    private final JsonMapper jsonMapper;
    private volatile Cached cached;

    public PremiumCompositionService(EconomyProperties defaults, PremiumSettingsRepository settings,
                                     EconomyRepository economyRepository, JsonMapper jsonMapper) {
        this.defaults = defaults;
        this.settings = settings;
        this.economyRepository = economyRepository;
        this.jsonMapper = jsonMapper;
    }

    public Composition current() {
        Cached snapshot = cached;
        if (snapshot == null || snapshot.loadedAt().plus(CACHE_TTL).isBefore(Instant.now())) {
            snapshot = new Cached(load(), Instant.now());
            cached = snapshot;
        }
        return snapshot.composition();
    }

    public Composition configDefaults() {
        return new Composition(defaults.premiumFeatures(), defaults.premiumAllowances(), false, false);
    }

    /**
     * Overrides the given lists (null = leave as is) and returns the new composition.
     */
    @Transactional
    public Composition update(List<String> features, List<Allowance> allowances, UUID actorAccountId) {
        if (features != null) {
            validateFeatures(features);
            settings.upsert(PremiumSettingsRepository.FEATURES, jsonMapper.writeValueAsString(features), actorAccountId);
        }
        if (allowances != null) {
            validateAllowances(allowances);
            settings.upsert(PremiumSettingsRepository.ALLOWANCES, jsonMapper.writeValueAsString(allowances), actorAccountId);
        }
        invalidateAfterCommit();
        return load();
    }

    /**
     * Drops both overrides: premium is composed from config again.
     */
    @Transactional
    public Composition resetToConfig() {
        settings.delete(PremiumSettingsRepository.FEATURES);
        settings.delete(PremiumSettingsRepository.ALLOWANCES);
        invalidateAfterCommit();
        return load();
    }

    /**
     * Uncached read (inside the caller's transaction, if any).
     */
    @Transactional(readOnly = true)
    public Composition load() {
        List<String> features = defaults.premiumFeatures();
        List<Allowance> allowances = defaults.premiumAllowances();
        boolean featuresOverridden = false;
        boolean allowancesOverridden = false;
        try {
            var storedFeatures = settings.find(PremiumSettingsRepository.FEATURES);
            if (storedFeatures.isPresent()) {
                features = List.copyOf(jsonMapper.readValue(storedFeatures.get(), FEATURES));
                featuresOverridden = true;
            }
            var storedAllowances = settings.find(PremiumSettingsRepository.ALLOWANCES);
            if (storedAllowances.isPresent()) {
                allowances = List.copyOf(jsonMapper.readValue(storedAllowances.get(), ALLOWANCES));
                allowancesOverridden = true;
            }
        } catch (RuntimeException e) {
            // A broken override must not lock everyone out of premium checks: fall back to config.
            log.error("Premium composition override is unreadable, using config defaults: {}", e.getMessage());
            return configDefaults();
        }
        return new Composition(features, allowances, featuresOverridden, allowancesOverridden);
    }

    private void invalidateAfterCommit() {
        cached = null;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    cached = null; // a read during the transaction may have cached the old state
                }
            });
        }
    }

    private static void validateFeatures(List<String> features) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (features.size() > MAX_FEATURES) {
            errors.put("features", "Не больше " + MAX_FEATURES + " функций");
        } else if (features.stream().anyMatch(f -> f == null || !FEATURE_CODE.matcher(f).matches())) {
            errors.put("features", "Коды функций: латинские заглавные буквы, цифры и _");
        } else if (new HashSet<>(features).size() != features.size()) {
            errors.put("features", "Коды функций не должны повторяться");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("VALIDATION_ERROR", "Ошибка валидации входных данных", errors);
        }
    }

    private void validateAllowances(List<Allowance> allowances) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (allowances.size() > MAX_ALLOWANCES) {
            errors.put("allowances", "Не больше " + MAX_ALLOWANCES + " выдач");
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < allowances.size() && errors.isEmpty(); i++) {
            Allowance allowance = allowances.get(i);
            String field = "allowances[" + i + "]";
            CatalogItem item = economyRepository.findActiveItem(allowance.itemCode()).orElse(null);
            if (item == null || item.kind() != ItemKind.CONSUMABLE) {
                errors.put(field + ".itemCode", "Нужен активный расходник из каталога");
            } else if (allowance.quantity() > MAX_ALLOWANCE_QUANTITY) {
                errors.put(field + ".quantity", "Не больше " + MAX_ALLOWANCE_QUANTITY);
            } else if (!keys.add(allowance.itemCode() + ":" + allowance.period())) {
                errors.put(field, "Этот расходник уже выдаётся с таким периодом");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("VALIDATION_ERROR", "Ошибка валидации входных данных", errors);
        }
    }
}
