package com.dating.achievements.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Admin-side access to achievement definitions (inactive ones included). Rewards are the same JSON
 * as read by AchievementService: [{"item": CODE, "quantity": n} | {"premiumDays": n}].
 */
@Repository
public class AchievementDefinitionAdminRepository {

    public record Row(String code, String title, String description, String rewardsJson, boolean active,
                      long completionCount) {
    }

    private static final String SELECT = """
            SELECT d.code, d.title, d.description, d.rewards::text, d.active,
                   (SELECT COUNT(*) FROM account_achievements a WHERE a.achievement_code = d.code)
            FROM achievement_definitions d
            """;

    private final JdbcClient jdbc;

    public AchievementDefinitionAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> all() {
        return jdbc.sql(SELECT + " ORDER BY d.code").query(this::row).list();
    }

    public Optional<Row> find(String code) {
        return jdbc.sql(SELECT + " WHERE d.code = :code").param("code", code).query(this::row).optional();
    }

    public boolean lock(String code) {
        return jdbc.sql("SELECT code FROM achievement_definitions WHERE code = :code FOR UPDATE")
                .param("code", code).query(String.class).optional().isPresent();
    }

    public void update(String code, String title, String description, String rewardsJson, boolean active) {
        jdbc.sql("""
                        UPDATE achievement_definitions
                        SET title = :title, description = :description, rewards = CAST(:rewards AS jsonb), active = :active
                        WHERE code = :code
                        """)
                .param("code", code)
                .param("title", title)
                .param("description", description)
                .param("rewards", rewardsJson)
                .param("active", active)
                .update();
    }

    private Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getLong(6));
    }
}
