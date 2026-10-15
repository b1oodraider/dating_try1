package com.dating.admin.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Append-only log of admin writes with before/after snapshots (any JSON-serializable object).
 * Written in the same transaction as the change itself, so a rolled-back change leaves no entry.
 */
@Component
public class AdminAuditLog {

    public record Entry(UUID id, UUID actorAccountId, String action, String entityType, String entityId,
                        JsonNode before, JsonNode after, Instant createdAt) {
    }

    public static final int MAX_LIMIT = 200;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public AdminAuditLog(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorAccountId, String action, String entityType, String entityId,
                       Object before, Object after) {
        jdbc.sql("""
                        INSERT INTO admin_audit_log (id, actor_account_id, action, entity_type, entity_id, before, after)
                        VALUES (:id, :actor, :action, :type, :entityId, CAST(:before AS jsonb), CAST(:after AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("actor", actorAccountId)
                .param("action", action)
                .param("type", entityType)
                .param("entityId", entityId)
                .param("before", before == null ? null : jsonMapper.writeValueAsString(before))
                .param("after", after == null ? null : jsonMapper.writeValueAsString(after))
                .update();
    }

    /**
     * Newest first. Filters are optional.
     */
    @Transactional(readOnly = true)
    public List<Entry> list(String entityType, String entityId, int limit) {
        return jdbc.sql("""
                        SELECT id, actor_account_id, action, entity_type, entity_id, before::text, after::text, created_at
                        FROM admin_audit_log
                        WHERE (CAST(:type AS text) IS NULL OR entity_type = :type)
                          AND (CAST(:entityId AS text) IS NULL OR entity_id = :entityId)
                        ORDER BY created_at DESC, id
                        LIMIT :limit
                        """)
                .param("type", entityType)
                .param("entityId", entityId)
                .param("limit", Math.clamp(limit, 1, MAX_LIMIT))
                .query((rs, n) -> new Entry(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        readJson(rs.getString(6)),
                        readJson(rs.getString(7)),
                        rs.getObject(8, OffsetDateTime.class).toInstant()))
                .list();
    }

    private JsonNode readJson(String json) {
        return json == null ? null : jsonMapper.readTree(json);
    }
}
