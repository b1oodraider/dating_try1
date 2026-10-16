package com.dating.achievements.application;

import com.dating.achievements.api.AchievementDto.RewardDto;
import com.dating.achievements.persistence.AchievementDefinitionAdminRepository;
import com.dating.common.errors.ApiException;
import com.dating.economy.application.EconomyService;
import com.dating.economy.domain.CatalogItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Editing achievement definitions: title, description, rewards and active. Codes are fixed because
 * triggers in code refer to them; new achievements come with their trigger in a release.
 * Rewards already lived in the DB (achievement_definitions.rewards), so editing them needs no migration.
 * A changed reward applies to completions from now on; earlier completions keep what they got.
 * <p>
 * Role checks and the audit log are done by the admin API around these calls.
 */
@Service
public class AchievementAdminService {

    public static final int MAX_REWARDS = 10;
    private static final long MAX_ITEM_QUANTITY = 1000;
    private static final TypeReference<List<Map<String, Object>>> REWARDS = new TypeReference<>() {
    };

    /**
     * @param rewards same shape as in GET /me/achievements
     */
    public record Definition(String code, String title, String description, List<RewardDto> rewards, boolean active,
                             long completionCount) {
    }

    public record Changes(String title, String description, List<RewardDto> rewards, boolean active) {
    }

    private final AchievementDefinitionAdminRepository repository;
    private final EconomyService economyService;
    private final JsonMapper jsonMapper;

    public AchievementAdminService(AchievementDefinitionAdminRepository repository, EconomyService economyService,
                                   JsonMapper jsonMapper) {
        this.repository = repository;
        this.economyService = economyService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public List<Definition> all() {
        return repository.all().stream().map(this::definition).toList();
    }

    @Transactional(readOnly = true)
    public Definition require(String code) {
        return repository.find(code).map(this::definition).orElseThrow(() -> notFound(code));
    }

    @Transactional
    public Definition update(String code, Changes changes) {
        if (!repository.lock(code)) {
            throw notFound(code);
        }
        repository.update(code, changes.title(), changes.description(), rewardsJson(changes.rewards()), changes.active());
        return require(code);
    }

    private String rewardsJson(List<RewardDto> rewards) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<Map<String, Object>> stored = new ArrayList<>();
        if (rewards.size() > MAX_REWARDS) {
            errors.put("rewards", "Не больше " + MAX_REWARDS + " наград");
        }
        for (int i = 0; i < rewards.size() && errors.isEmpty(); i++) {
            RewardDto reward = rewards.get(i);
            String field = "rewards[" + i + "]";
            if ("PREMIUM_DAYS".equals(reward.type())) {
                if (reward.days() == null || reward.days() < 1 || reward.days() > 3650) {
                    errors.put(field + ".days", "Дней премиума: 1..3650");
                } else {
                    stored.add(Map.of("premiumDays", reward.days()));
                }
            } else if ("ITEM".equals(reward.type())) {
                long quantity = reward.quantity() == null ? 1 : reward.quantity();
                CatalogItem item = reward.itemCode() == null ? null : findItem(reward.itemCode());
                if (item == null) {
                    errors.put(field + ".itemCode", "Нужен активный предмет из каталога");
                } else if (quantity < 1 || quantity > MAX_ITEM_QUANTITY || (!item.kind().stackable() && quantity != 1)) {
                    errors.put(field + ".quantity", item.kind().stackable()
                            ? "Количество: 1.." + MAX_ITEM_QUANTITY : "Косметика выдаётся в одном экземпляре");
                } else {
                    stored.add(Map.of("item", item.code(), "quantity", quantity));
                }
            } else {
                errors.put(field + ".type", "Тип награды: ITEM или PREMIUM_DAYS");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("VALIDATION_ERROR", "Ошибка валидации входных данных", errors);
        }
        return jsonMapper.writeValueAsString(stored);
    }

    private CatalogItem findItem(String code) {
        try {
            return economyService.requireItem(code);
        } catch (ApiException e) {
            return null;
        }
    }

    private Definition definition(AchievementDefinitionAdminRepository.Row row) {
        List<RewardDto> rewards = jsonMapper.readValue(row.rewardsJson(), REWARDS).stream()
                .map(raw -> raw.containsKey("premiumDays")
                        ? new RewardDto("PREMIUM_DAYS", null, null, ((Number) raw.get("premiumDays")).intValue())
                        : new RewardDto("ITEM", (String) raw.get("item"),
                        ((Number) raw.getOrDefault("quantity", 1)).longValue(), null))
                .toList();
        return new Definition(row.code(), row.title(), row.description(), rewards, row.active(), row.completionCount());
    }

    private static ApiException notFound(String code) {
        return ApiException.notFound("ACHIEVEMENT_NOT_FOUND", "Достижение не найдено: " + code);
    }
}
