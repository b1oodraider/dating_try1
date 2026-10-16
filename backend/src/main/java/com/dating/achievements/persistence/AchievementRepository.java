package com.dating.achievements.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AchievementRepository {

    /**
     * @param rewardsJson JSON array: {"item": CODE, "quantity": n} | {"premiumDays": n}
     */
    public record DefinitionRow(String code, String title, String description, String rewardsJson) {
    }

    private final JdbcClient jdbc;

    public AchievementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DefinitionRow> activeDefinitions() {
        return jdbc.sql("SELECT code, title, description, rewards::text FROM achievement_definitions WHERE active ORDER BY code")
                .query((rs, n) -> new DefinitionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
    }

    public Optional<DefinitionRow> findActiveDefinition(String code) {
        return jdbc.sql("SELECT code, title, description, rewards::text FROM achievement_definitions WHERE code = :code AND active")
                .param("code", code)
                .query((rs, n) -> new DefinitionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional();
    }

    public Map<String, Instant> completions(UUID accountId) {
        Map<String, Instant> completions = new LinkedHashMap<>();
        jdbc.sql("SELECT achievement_code, completed_at FROM account_achievements WHERE account_id = :a")
                .param("a", accountId)
                .query(rs -> {
                    completions.put(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant());
                });
        return completions;
    }

    /**
     * @return false if the achievement was already completed
     */
    public boolean insertCompletion(UUID accountId, String code) {
        return jdbc.sql("""
                        INSERT INTO account_achievements (account_id, achievement_code) VALUES (:a, :code)
                        ON CONFLICT (account_id, achievement_code) DO NOTHING
                        """)
                .param("a", accountId)
                .param("code", code)
                .update() == 1;
    }

    public void deleteCompletion(UUID accountId, String code) {
        jdbc.sql("DELETE FROM account_achievements WHERE account_id = :a AND achievement_code = :code")
                .param("a", accountId)
                .param("code", code)
                .update();
    }

    public void deleteAll(UUID accountId) {
        jdbc.sql("DELETE FROM account_achievements WHERE account_id = :a").param("a", accountId).update();
    }
}
