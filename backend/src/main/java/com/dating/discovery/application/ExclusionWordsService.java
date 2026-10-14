package com.dating.discovery.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.common.entitlements.FeatureAccess;
import com.dating.common.entitlements.PremiumFeatures;
import com.dating.common.errors.ApiException;
import com.dating.discovery.domain.DiscoveryPreferences;
import com.dating.discovery.persistence.DiscoveryPreferencesRepository;
import com.dating.productevents.application.ProductEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * "Exclusion words": a private list of words; profiles mentioning any of them (in any word form)
 * are hidden from the owner's discovery feed.
 */
@Service
public class ExclusionWordsService {

    public static final int MAX_WORDS = 20;
    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 30;

    /**
     * Letters and digits only. Besides validation this guarantees the words cannot inject
     * tsquery operators when the query string is built.
     */
    private static final Pattern WORD = Pattern.compile("^[\\p{L}\\p{N}]+$");

    private final DiscoveryPreferencesRepository repository;
    private final ProductEventPublisher productEvents;
    private final FeatureAccess featureAccess;

    public ExclusionWordsService(DiscoveryPreferencesRepository repository, ProductEventPublisher productEvents,
                                 FeatureAccess featureAccess) {
        this.repository = repository;
        this.productEvents = productEvents;
        this.featureAccess = featureAccess;
    }

    @Transactional(readOnly = true)
    public List<String> wordsOf(UUID accountId) {
        return repository.findById(accountId)
                .map(DiscoveryPreferences::getExclusionWords)
                .orElse(List.of());
    }

    /**
     * Words to apply in discovery: empty when the feature is not available (e.g. premium has ended);
     * the saved list is kept and applies again once premium is back.
     */
    @Transactional(readOnly = true)
    public List<String> activeWordsOf(UUID accountId) {
        return isActive(accountId) ? wordsOf(accountId) : List.of();
    }

    public boolean isActive(UUID accountId) {
        return featureAccess.isAllowed(accountId, PremiumFeatures.EXCLUSION_WORDS);
    }

    @Transactional
    public List<String> replaceWords(UUID accountId, List<String> rawWords) {
        featureAccess.require(accountId, PremiumFeatures.EXCLUSION_WORDS);
        List<String> words = normalize(rawWords);
        DiscoveryPreferences preferences = repository.findById(accountId)
                .orElseGet(() -> new DiscoveryPreferences(accountId));
        preferences.replaceExclusionWords(words);
        repository.save(preferences);
        // Only the count: the words themselves are private.
        productEvents.publish("exclusion_words_updated", Map.of("count", words.size()));
        return words;
    }

    /**
     * Trim, lowercase, drop duplicates (keeping order) and validate.
     */
    static List<String> normalize(List<String> rawWords) {
        Set<String> words = new LinkedHashSet<>();
        for (String raw : rawWords) {
            // "ё" is folded to "е": documents are folded the same way in the database (migration 006).
            String word = Objects.requireNonNullElse(raw, "").trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (word.length() < MIN_LENGTH || word.length() > MAX_LENGTH || !WORD.matcher(word).matches()) {
                throw invalid("Каждое слово — от 2 до 30 букв или цифр, без пробелов и знаков: «" + abbreviate(raw) + "»");
            }
            words.add(word);
        }
        if (words.size() > MAX_WORDS) {
            throw invalid("Можно указать не более " + MAX_WORDS + " слов");
        }
        return List.copyOf(words);
    }

    /**
     * PostgreSQL tsquery matching any of the words; safe because words are letters/digits only.
     */
    static String toTsQuery(List<String> words) {
        return String.join(" | ", words);
    }

    private static ApiException invalid(String message) {
        return ApiException.validation("VALIDATION_ERROR", "Ошибка валидации входных данных", Map.of("words", message));
    }

    private static String abbreviate(String raw) {
        String value = Objects.requireNonNullElse(raw, "");
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        repository.findById(event.accountId()).ifPresent(repository::delete);
    }
}
