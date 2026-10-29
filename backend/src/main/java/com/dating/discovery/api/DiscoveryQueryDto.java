package com.dating.discovery.api;

import com.dating.common.errors.ApiException;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @param minAge    narrows the viewer's saved age range for this request (the saved range always applies)
 * @param city      legacy free text, resolved to a dictionary city when possible; prefer cityId
 * @param cityId    only profiles of this city (GET /cities)
 * @param radiusKm  overrides the saved search radius for this request
 * @param minHeight premium (ADVANCED_FILTERS): height in cm, profiles without height do not match
 * @param goalCodes profile has at least one of these goals (free)
 */
public record DiscoveryQueryDto(
        String city,
        Integer minAge,
        Integer maxAge,
        Set<String> tagCodes,
        TagLogic tagLogic,
        Integer minHeight,
        Integer maxHeight,
        Set<String> goalCodes,
        int page,
        int limit,
        Integer cityId,
        Integer radiusKm,
        /** Interest groups: only people subscribed to at least one of them. */
        Set<Long> communityIds
) {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    private static final int MAX_TAGS = 10;

    public DiscoveryQueryDto {
        page = Math.max(0, page);
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        tagLogic = tagLogic == null ? TagLogic.OR : tagLogic;
        city = city == null || city.isBlank() || city.trim().equalsIgnoreCase("ALL") ? null : city.trim();
        tagCodes = clean(tagCodes);
        goalCodes = clean(goalCodes);
        communityIds = communityIds == null ? Set.of() : Set.copyOf(communityIds);
        if (communityIds.size() > 50) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Слишком много групп в фильтре");
        }
        if (tagCodes.size() > MAX_TAGS) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Можно выбрать не более " + MAX_TAGS + " карточек в фильтре");
        }
        if (radiusKm != null && (radiusKm < 1 || radiusKm > 20000)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Радиус поиска — от 1 до 20000 км");
        }
        if (minAge != null && maxAge != null && minAge > maxAge) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Минимальный возраст больше максимального");
        }
        if (minHeight != null && maxHeight != null && minHeight > maxHeight) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Минимальный рост больше максимального");
        }
    }

    public boolean usesAdvancedFilters() {
        return minHeight != null || maxHeight != null;
    }

    private static Set<String> clean(Set<String> codes) {
        return codes == null ? Set.of() : codes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
