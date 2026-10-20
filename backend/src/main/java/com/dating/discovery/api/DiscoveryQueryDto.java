package com.dating.discovery.api;

import com.dating.common.errors.ApiException;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @param minAge    narrows the viewer's saved age range for this request (the saved range always applies)
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
        int limit
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
        if (tagCodes.size() > MAX_TAGS) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Можно выбрать не более " + MAX_TAGS + " карточек в фильтре");
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
