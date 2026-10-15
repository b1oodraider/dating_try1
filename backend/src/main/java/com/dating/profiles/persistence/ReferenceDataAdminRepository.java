package com.dating.profiles.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-side reads and writes of profile reference data: communication tags, their categories and
 * relationship goals. Plain SQL, inactive rows included, with usage counts for the "delete or hide" rule.
 */
@Repository
public class ReferenceDataAdminRepository {

    public record TagRow(String code, String category, String categoryLabel, String label, String description,
                         int sortOrder, boolean active, long usageCount) {
    }

    public record CategoryRow(String code, String label, int sortOrder, long tagCount) {
    }

    public record GoalRow(String code, String label, int sortOrder, boolean active, long usageCount) {
    }

    private static final String TAG_SELECT = """
            SELECT t.code, t.category, t.category_label, t.label, t.description, t.sort_order, t.active,
                   (SELECT COUNT(*) FROM profile_communication_tags p WHERE p.tag_id = t.id)
            FROM communication_tags t
            """;
    private static final String GOAL_SELECT = """
            SELECT g.code, g.label, g.sort_order, g.active,
                   (SELECT COUNT(*) FROM profile_goals p WHERE p.goal_code = g.code)
                   + (SELECT COUNT(*) FROM discovery_excluded_goals d WHERE d.goal_code = g.code)
            FROM relationship_goals g
            """;

    private final JdbcClient jdbc;

    public ReferenceDataAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- tags

    public List<TagRow> tags() {
        return jdbc.sql(TAG_SELECT + " ORDER BY t.sort_order, t.code").query(this::mapTag).list();
    }

    public Optional<TagRow> tag(String code) {
        return jdbc.sql(TAG_SELECT + " WHERE t.code = :code").param("code", code).query(this::mapTag).optional();
    }

    /**
     * @return false if a tag with this code already exists
     */
    public boolean insertTag(String code, String category, String label, String description, int sortOrder, boolean active) {
        return jdbc.sql("""
                        INSERT INTO communication_tags (id, code, category, category_label, label, description, sort_order, active)
                        SELECT :id, :code, c.code, c.label, :label, :description, :sortOrder, :active
                        FROM tag_categories c WHERE c.code = :category
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("code", code)
                .param("category", category)
                .param("label", label)
                .param("description", description)
                .param("sortOrder", sortOrder)
                .param("active", active)
                .update() == 1;
    }

    public void updateTag(String code, String category, String label, String description, int sortOrder, boolean active) {
        jdbc.sql("""
                        UPDATE communication_tags t
                        SET category = c.code, category_label = c.label, label = :label, description = :description,
                            sort_order = :sortOrder, active = :active
                        FROM tag_categories c
                        WHERE t.code = :code AND c.code = :category
                        """)
                .param("code", code)
                .param("category", category)
                .param("label", label)
                .param("description", description)
                .param("sortOrder", sortOrder)
                .param("active", active)
                .update();
    }

    /**
     * Locks the row until the end of the transaction: profiles cannot start referencing it meanwhile
     * (their FK insert needs a key-share lock), so a usage count taken afterwards stays true.
     *
     * @param table one of communication_tags, tag_categories, relationship_goals
     */
    public boolean lock(String table, String code) {
        if (!List.of("communication_tags", "tag_categories", "relationship_goals").contains(table)) {
            throw new IllegalArgumentException("Unknown table " + table);
        }
        return jdbc.sql("SELECT code FROM " + table + " WHERE code = :code FOR UPDATE")
                .param("code", code)
                .query(String.class)
                .optional()
                .isPresent();
    }

    public void deleteTag(String code) {
        jdbc.sql("DELETE FROM communication_tags WHERE code = :code").param("code", code).update();
    }

    // ---------------------------------------------------------------- categories

    public List<CategoryRow> categories() {
        return jdbc.sql("""
                        SELECT c.code, c.label, c.sort_order, (SELECT COUNT(*) FROM communication_tags t WHERE t.category = c.code)
                        FROM tag_categories c ORDER BY c.sort_order, c.code
                        """)
                .query((rs, n) -> new CategoryRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4)))
                .list();
    }

    public Optional<CategoryRow> category(String code) {
        return categories().stream().filter(c -> c.code().equals(code)).findFirst();
    }

    public boolean insertCategory(String code, String label, int sortOrder) {
        return jdbc.sql("""
                        INSERT INTO tag_categories (code, label, sort_order) VALUES (:code, :label, :sortOrder)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("code", code)
                .param("label", label)
                .param("sortOrder", sortOrder)
                .update() == 1;
    }

    /**
     * Also refreshes the denormalized category_label of the category's tags.
     */
    public void updateCategory(String code, String label, int sortOrder) {
        jdbc.sql("UPDATE tag_categories SET label = :label, sort_order = :sortOrder WHERE code = :code")
                .param("code", code)
                .param("label", label)
                .param("sortOrder", sortOrder)
                .update();
        jdbc.sql("UPDATE communication_tags SET category_label = :label WHERE category = :code")
                .param("code", code)
                .param("label", label)
                .update();
    }

    public void deleteCategory(String code) {
        jdbc.sql("DELETE FROM tag_categories WHERE code = :code").param("code", code).update();
    }

    // ---------------------------------------------------------------- goals

    public List<GoalRow> goals() {
        return jdbc.sql(GOAL_SELECT + " ORDER BY g.sort_order, g.code").query(this::mapGoal).list();
    }

    public Optional<GoalRow> goal(String code) {
        return jdbc.sql(GOAL_SELECT + " WHERE g.code = :code").param("code", code).query(this::mapGoal).optional();
    }

    public boolean insertGoal(String code, String label, int sortOrder, boolean active) {
        return jdbc.sql("""
                        INSERT INTO relationship_goals (code, label, sort_order, active) VALUES (:code, :label, :sortOrder, :active)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("code", code)
                .param("label", label)
                .param("sortOrder", sortOrder)
                .param("active", active)
                .update() == 1;
    }

    public void updateGoal(String code, String label, int sortOrder, boolean active) {
        jdbc.sql("UPDATE relationship_goals SET label = :label, sort_order = :sortOrder, active = :active WHERE code = :code")
                .param("code", code)
                .param("label", label)
                .param("sortOrder", sortOrder)
                .param("active", active)
                .update();
    }

    public void deleteGoal(String code) {
        jdbc.sql("DELETE FROM relationship_goals WHERE code = :code").param("code", code).update();
    }

    private TagRow mapTag(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new TagRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getInt(6), rs.getBoolean(7), rs.getLong(8));
    }

    private GoalRow mapGoal(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new GoalRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4), rs.getLong(5));
    }
}
