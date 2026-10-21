package com.dating.profiles.application;

import com.dating.common.errors.ApiException;
import com.dating.profiles.domain.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two-way fact filters (docs/matching.md, part 1): «accept» lists the allowed answers, «unspecified» also shows those
 * who did not answer. Applied in SQL by facts_match (changeset 031) in both directions.
 */
@Service
public class FactFiltersService {

    /** @param accept allowed answers (codes); @param unspecified also show people who did not say */
    public record Filter(List<String> accept, boolean unspecified) {
    }

    private final ProfileService profiles;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public FactFiltersService(ProfileService profiles, JdbcClient jdbc, JsonMapper json) {
        this.profiles = profiles;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Map<String, Filter> get(UUID accountId) {
        String raw = jdbc.sql("SELECT fact_filters::text FROM profiles WHERE account_id = :a")
                .param("a", accountId).query(String.class).optional().orElse(null);
        if (raw == null) {
            return Map.of();
        }
        Map<String, Filter> result = new LinkedHashMap<>();
        json.readTree(raw).properties().forEach(e -> result.put(e.getKey(), new Filter(
                e.getValue().path("accept").valueStream().map(n -> n.asString()).toList(),
                e.getValue().path("unspecified").asBoolean(true))));
        return result;
    }

    /** Replaces all filters; a fact left out is «не важно». */
    @Transactional
    public Map<String, Filter> put(UUID accountId, Map<String, Filter> filters) {
        Map<String, String> errors = new LinkedHashMap<>();
        filters.forEach((fact, filter) -> {
            Map<String, String> allowed = ProfileFacts.FILTERABLE.get(fact);
            if (allowed == null) {
                errors.put(fact, "Неизвестный фильтр");
            } else if (filter == null || filter.accept() == null || filter.accept().isEmpty()) {
                errors.put(fact, "Выберите хотя бы один вариант или уберите фильтр");
            } else if (!allowed.keySet().containsAll(filter.accept())) {
                errors.put(fact, "Неизвестные значения");
            }
        });
        if (!errors.isEmpty()) {
            throw ApiException.validation("VALIDATION_ERROR", "Ошибка в фильтрах", errors);
        }
        Profile profile = profiles.lockOrCreateProfile(accountId);
        jdbc.sql("UPDATE profiles SET fact_filters = CAST(:f AS jsonb) WHERE id = :id")
                .param("f", filters.isEmpty() ? null : json.writeValueAsString(filters))
                .param("id", profile.getId())
                .update();
        return get(accountId);
    }
}
