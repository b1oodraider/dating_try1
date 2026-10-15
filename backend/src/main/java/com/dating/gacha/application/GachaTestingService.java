package com.dating.gacha.application;

import com.dating.common.errors.ApiException;
import com.dating.gacha.api.GachaTestingStateDto;
import com.dating.gacha.domain.PityState;
import com.dating.gacha.domain.PoolEntry;
import com.dating.gacha.persistence.GachaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * QA tools for test accounts (called only from the testing API): deterministic results, pity counters, history.
 * The forced queue is read by pulls only while app.testing.enabled=true.
 */
@Service
public class GachaTestingService {

    public static final int MAX_FORCED = 100;
    private static final Pattern GROUP = Pattern.compile("[A-Z0-9_]{2,50}");

    private final GachaRepository repository;

    public GachaTestingService(GachaRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public GachaTestingStateDto state(UUID accountId) {
        return new GachaTestingStateDto(repository.forced(accountId), repository.pityStates(accountId),
                repository.countPulls(accountId));
    }

    /**
     * Replaces the forced queue of a banner: the next pulls there return these entries in order
     * (pity counters still advance by their rarity; guarantees do not override forced results).
     *
     * @throws ApiException 404 BANNER_NOT_FOUND / POOL_ENTRY_NOT_FOUND
     */
    @Transactional
    public GachaTestingStateDto forceNext(UUID accountId, String bannerCode, List<String> entryIds) {
        repository.findBanner(bannerCode).orElseThrow(() -> GachaErrors.bannerNotFound(bannerCode));
        if (entryIds.size() > MAX_FORCED) {
            throw ApiException.validation("VALIDATION_ERROR", "Ошибка валидации входных данных",
                    Map.of("entryIds", "Не больше " + MAX_FORCED));
        }
        Set<String> known = repository.pool(bannerCode, true).stream()
                .map(PoolEntry::entryId)
                .collect(Collectors.toSet());
        for (String entryId : entryIds) {
            if (!known.contains(entryId)) {
                throw GachaErrors.entryNotFound(bannerCode, entryId);
            }
        }
        repository.lockAccount(accountId);
        repository.replaceForced(accountId, bannerCode, entryIds);
        return state(accountId);
    }

    @Transactional
    public GachaTestingStateDto clearForced(UUID accountId) {
        repository.lockAccount(accountId);
        repository.deleteForced(accountId);
        return state(accountId);
    }

    /**
     * Sets the counters of a pity group, e.g. 79 pulls since a LEGENDARY → the next pull is the guaranteed one.
     */
    @Transactional
    public GachaTestingStateDto setPity(UUID accountId, String pityGroup, int pullsSinceEpic, int pullsSinceLegendary) {
        if (pityGroup == null || !GROUP.matcher(pityGroup).matches()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Некорректная группа гарантии: " + pityGroup);
        }
        repository.lockAccount(accountId);
        PityState current = repository.lockPity(accountId, pityGroup);
        repository.savePity(accountId, pityGroup, new PityState(pullsSinceEpic, pullsSinceLegendary, current.totalPulls()));
        return state(accountId);
    }

    /** Removes all pity counters of the account (every group starts from zero). */
    @Transactional
    public GachaTestingStateDto resetPity(UUID accountId) {
        repository.lockAccount(accountId);
        repository.deletePity(accountId);
        return state(accountId);
    }

    /**
     * Deletes the pull history and stored pull requests (their idempotency keys can be used again).
     * Wallet and pity counters are not changed.
     */
    @Transactional
    public GachaTestingStateDto clearHistory(UUID accountId) {
        repository.lockAccount(accountId);
        repository.deleteHistory(accountId);
        return state(accountId);
    }
}
