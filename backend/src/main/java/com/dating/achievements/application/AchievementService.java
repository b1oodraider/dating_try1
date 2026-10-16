package com.dating.achievements.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.achievements.api.AchievementDto;
import com.dating.achievements.persistence.AchievementRepository;
import com.dating.common.errors.ApiException;
import com.dating.economy.application.EconomyService;
import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal achievement registry: definitions with rewards (data) and completions.
 * Automatic triggers will subscribe to domain events (AccountCreatedEvent, ProfilePublishedEvent,
 * ProfilePhotoAddedEvent, …) and call {@link #complete}; today completions come from the testing API.
 */
@Service
public class AchievementService {

    private static final TypeReference<List<Map<String, Object>>> REWARDS = new TypeReference<>() {
    };

    private final AchievementRepository repository;
    private final EconomyService economyService;
    private final JsonMapper jsonMapper;

    public AchievementService(AchievementRepository repository, EconomyService economyService, JsonMapper jsonMapper) {
        this.repository = repository;
        this.economyService = economyService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public List<AchievementDto> list(UUID accountId) {
        Map<String, Instant> completions = repository.completions(accountId);
        return repository.activeDefinitions().stream()
                .map(d -> new AchievementDto(d.code(), d.title(), d.description(), rewardDtos(d.rewardsJson()),
                        completions.containsKey(d.code()), completions.get(d.code())))
                .toList();
    }

    /**
     * Marks the achievement completed and grants its rewards exactly once.
     *
     * @return false if it was already completed
     */
    @Transactional
    public boolean complete(UUID accountId, String code) {
        AchievementRepository.DefinitionRow definition = repository.findActiveDefinition(code)
                .orElseThrow(() -> ApiException.notFound("ACHIEVEMENT_NOT_FOUND", "Достижение не найдено: " + code));
        if (!repository.insertCompletion(accountId, code)) {
            return false;
        }
        List<Reward> rewards = rewards(definition.rewardsJson());
        if (!rewards.isEmpty()) {
            economyService.grant(accountId, RewardSource.ACHIEVEMENT, code, idempotencyKey(code), rewards);
        }
        return true;
    }

    /**
     * Testing support: forget the completion so it can be completed (and rewarded) again.
     * Already granted rewards are kept.
     */
    @Transactional
    public void resetForTesting(UUID accountId, String code) {
        repository.deleteCompletion(accountId, code);
        economyService.forgetTransaction(accountId, idempotencyKey(code));
    }

    private static String idempotencyKey(String code) {
        return "achievement:" + code;
    }

    private List<Reward> rewards(String json) {
        List<Reward> rewards = new ArrayList<>();
        for (Map<String, Object> raw : jsonMapper.readValue(json, REWARDS)) {
            if (raw.containsKey("premiumDays")) {
                rewards.add(new Reward.PremiumDays(((Number) raw.get("premiumDays")).intValue()));
            } else {
                Number quantity = (Number) raw.getOrDefault("quantity", 1);
                rewards.add(new Reward.Item((String) raw.get("item"), quantity.longValue()));
            }
        }
        return rewards;
    }

    private List<AchievementDto.RewardDto> rewardDtos(String json) {
        return rewards(json).stream()
                .map(r -> switch (r) {
                    case Reward.Item item -> new AchievementDto.RewardDto("ITEM", item.itemCode(), item.quantity(), null);
                    case Reward.PremiumDays days -> new AchievementDto.RewardDto("PREMIUM_DAYS", null, null, days.days());
                })
                .toList();
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        repository.deleteAll(event.accountId());
    }
}
