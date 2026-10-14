package com.dating.discovery.api;

import com.dating.common.errors.ApiException;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record DiscoveryQueryDto(
        String city,
        Integer minAge,
        Integer maxAge,
        Set<String> tagCodes,
        TagLogic tagLogic,
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
        tagCodes = tagCodes == null ? Set.of() : tagCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (tagCodes.size() > MAX_TAGS) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Можно выбрать не более " + MAX_TAGS + " карточек в фильтре");
        }
        if (minAge != null && maxAge != null && minAge > maxAge) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Минимальный возраст больше максимального");
        }
    }
}
