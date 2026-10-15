package com.dating.economy.persistence;

import com.dating.economy.domain.Author;
import com.dating.economy.domain.CatalogEntry;
import com.dating.economy.domain.ItemAvailability;
import com.dating.economy.domain.ItemKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalog management (all items, including inactive ones), availability windows and authors.
 * The wallet path keeps using EconomyRepository and the slim CatalogItem.
 */
@Repository
public class CatalogRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final String ENTRY_SELECT = """
            SELECT c.code, c.kind, c.category, c.equip_slot, c.title, c.rarity, c.attributes::text, c.active,
                   c.available_from, c.available_until, c.author_id, a.display_name, c.created_at, c.updated_at
            FROM catalog_items c LEFT JOIN authors a ON a.id = c.author_id
            """;
    private static final String AUTHOR_SELECT = """
            SELECT a.id, a.display_name, a.account_id, a.revenue_share_percent, a.contact_note, a.created_at, a.updated_at,
                   (SELECT COUNT(*) FROM catalog_items c WHERE c.author_id = a.id)
            FROM authors a
            """;

    /**
     * Fields an editor can change after creation (code, kind, category and slot are fixed).
     */
    public record ItemChanges(String title, String rarity, Map<String, Object> attributes, boolean active,
                              Instant availableFrom, Instant availableUntil, UUID authorId) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public CatalogRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    // ---------------------------------------------------------------- items

    /**
     * @param activeOnly only active items (public catalog)
     * @param kind       optional filter
     * @param category   optional filter
     */
    public List<CatalogEntry> entries(boolean activeOnly, String kind, String category) {
        return jdbc.sql(ENTRY_SELECT + """
                        WHERE (NOT :activeOnly OR c.active)
                          AND (CAST(:kind AS text) IS NULL OR c.kind = :kind)
                          AND (CAST(:category AS text) IS NULL OR c.category = :category)
                        ORDER BY c.kind, c.category, c.code
                        """)
                .param("activeOnly", activeOnly)
                .param("kind", kind)
                .param("category", category)
                .query(this::mapEntry)
                .list();
    }

    public Optional<CatalogEntry> entry(String code) {
        return jdbc.sql(ENTRY_SELECT + " WHERE c.code = :code").param("code", code).query(this::mapEntry).optional();
    }

    /**
     * Locks the row (admin edits of one item are serialized) and returns it.
     */
    public Optional<CatalogEntry> lockEntry(String code) {
        boolean exists = jdbc.sql("SELECT code FROM catalog_items WHERE code = :code FOR UPDATE")
                .param("code", code).query(String.class).optional().isPresent();
        return exists ? entry(code) : Optional.empty();
    }

    public Optional<ItemAvailability> availability(String code) {
        return jdbc.sql("SELECT available_from, available_until FROM catalog_items WHERE code = :code")
                .param("code", code)
                .query((rs, n) -> new ItemAvailability(instant(rs, 1), instant(rs, 2)))
                .optional();
    }

    /**
     * @return false if an item with this code already exists
     */
    public boolean insert(String code, ItemKind kind, String category, String equipSlot, ItemChanges fields) {
        return jdbc.sql("""
                        INSERT INTO catalog_items (code, kind, category, equip_slot, title, rarity, attributes, active,
                                                   available_from, available_until, author_id)
                        VALUES (:code, :kind, :category, :slot, :title, :rarity, CAST(:attributes AS jsonb), :active,
                                :from, :until, :authorId)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("code", code)
                .param("kind", kind.name())
                .param("category", category)
                .param("slot", equipSlot)
                .params(changeParams(fields))
                .update() == 1;
    }

    public void update(String code, ItemChanges fields) {
        jdbc.sql("""
                        UPDATE catalog_items
                        SET title = :title, rarity = :rarity, attributes = CAST(:attributes AS jsonb), active = :active,
                            available_from = :from, available_until = :until, author_id = :authorId, updated_at = now()
                        WHERE code = :code
                        """)
                .param("code", code)
                .params(changeParams(fields))
                .update();
    }

    private Map<String, Object> changeParams(ItemChanges fields) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("title", fields.title());
        params.put("rarity", fields.rarity());
        params.put("attributes", jsonMapper.writeValueAsString(fields.attributes()));
        params.put("active", fields.active());
        params.put("from", offset(fields.availableFrom()));
        params.put("until", offset(fields.availableUntil()));
        params.put("authorId", fields.authorId());
        return params;
    }

    // ---------------------------------------------------------------- authors

    public List<Author> authors() {
        return jdbc.sql(AUTHOR_SELECT + " ORDER BY a.display_name, a.id").query(this::mapAuthor).list();
    }

    public Optional<Author> author(UUID id) {
        return jdbc.sql(AUTHOR_SELECT + " WHERE a.id = :id").param("id", id).query(this::mapAuthor).optional();
    }

    public boolean lockAuthor(UUID id) {
        return jdbc.sql("SELECT id FROM authors WHERE id = :id FOR UPDATE")
                .param("id", id).query(UUID.class).optional().isPresent();
    }

    public void insertAuthor(UUID id, String displayName, UUID accountId, int revenueSharePercent, String contactNote) {
        jdbc.sql("""
                        INSERT INTO authors (id, display_name, account_id, revenue_share_percent, contact_note)
                        VALUES (:id, :name, :accountId, :share, :note)
                        """)
                .param("id", id)
                .param("name", displayName)
                .param("accountId", accountId)
                .param("share", revenueSharePercent)
                .param("note", contactNote)
                .update();
    }

    public void updateAuthor(UUID id, String displayName, UUID accountId, int revenueSharePercent, String contactNote) {
        jdbc.sql("""
                        UPDATE authors SET display_name = :name, account_id = :accountId, revenue_share_percent = :share,
                                           contact_note = :note, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("name", displayName)
                .param("accountId", accountId)
                .param("share", revenueSharePercent)
                .param("note", contactNote)
                .update();
    }

    public void deleteAuthor(UUID id) {
        jdbc.sql("DELETE FROM authors WHERE id = :id").param("id", id).update();
    }

    /**
     * The author stays (contracts and payouts outlive app accounts); only the link to the account goes.
     */
    public void unlinkAccount(UUID accountId) {
        jdbc.sql("UPDATE authors SET account_id = NULL, updated_at = now() WHERE account_id = :a")
                .param("a", accountId)
                .update();
    }

    public boolean accountExists(UUID accountId) {
        return jdbc.sql("SELECT COUNT(*) FROM accounts WHERE id = :id AND status <> 'DELETED'")
                .param("id", accountId).query(Long.class).single() > 0;
    }

    // ---------------------------------------------------------------- mapping

    private CatalogEntry mapEntry(ResultSet rs, int n) throws SQLException {
        String attributes = rs.getString(7);
        return new CatalogEntry(
                rs.getString(1),
                ItemKind.valueOf(rs.getString(2)),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                attributes == null ? Map.of() : jsonMapper.readValue(attributes, MAP),
                rs.getBoolean(8),
                instant(rs, 9),
                instant(rs, 10),
                rs.getObject(11, UUID.class),
                rs.getString(12),
                instant(rs, 13),
                instant(rs, 14));
    }

    private Author mapAuthor(ResultSet rs, int n) throws SQLException {
        return new Author(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getInt(4),
                rs.getString(5), instant(rs, 6), instant(rs, 7), rs.getLong(8));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
