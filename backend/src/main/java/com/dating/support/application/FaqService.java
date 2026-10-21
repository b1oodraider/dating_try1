package com.dating.support.application;

import com.dating.admin.application.AdminAuditLog;
import com.dating.common.errors.ApiException;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * FAQ of the help screen (changeset 032): read by everyone, edited by CONTENT_EDITOR, SUPPORT or ADMIN (audited).
 */
@Service
public class FaqService {

    private static final Role[] EDITORS = {Role.CONTENT_EDITOR, Role.SUPPORT, Role.ADMIN};
    private static final String ENTITY = "FAQ_ITEM";

    public record FaqEntry(UUID id, String question, String answer, int sortOrder) {
    }

    public record FaqItem(UUID id, String question, String answer, int sortOrder, boolean active,
                          OffsetDateTime updatedAt) {
    }

    public record Change(String question, String answer, int sortOrder, boolean active) {
    }

    private final JdbcClient jdbc;
    private final RoleAccess roleAccess;
    private final AdminAuditLog auditLog;

    public FaqService(JdbcClient jdbc, RoleAccess roleAccess, AdminAuditLog auditLog) {
        this.jdbc = jdbc;
        this.roleAccess = roleAccess;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public List<FaqEntry> published() {
        return jdbc.sql("SELECT id, question, answer, sort_order FROM faq_items WHERE active ORDER BY sort_order, question")
                .query(FaqEntry.class).list();
    }

    @Transactional(readOnly = true)
    public List<FaqItem> all(UUID actor) {
        roleAccess.require(actor, EDITORS);
        return jdbc.sql("SELECT * FROM faq_items ORDER BY sort_order, question").query(FaqItem.class).list();
    }

    @Transactional
    public FaqItem create(UUID actor, Change change) {
        roleAccess.require(actor, EDITORS);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO faq_items (id, question, answer, sort_order, active)
                        VALUES (:id, :question, :answer, :sortOrder, :active)
                        """)
                .param("id", id).paramSource(change).update();
        FaqItem created = find(id);
        auditLog.record(actor, "CREATE", ENTITY, id.toString(), null, created);
        return created;
    }

    @Transactional
    public FaqItem update(UUID actor, UUID id, Change change) {
        roleAccess.require(actor, EDITORS);
        FaqItem before = find(id);
        jdbc.sql("""
                        UPDATE faq_items SET question = :question, answer = :answer, sort_order = :sortOrder,
                                             active = :active, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id).paramSource(change).update();
        FaqItem after = find(id);
        auditLog.record(actor, "UPDATE", ENTITY, id.toString(), before, after);
        return after;
    }

    @Transactional
    public void delete(UUID actor, UUID id) {
        roleAccess.require(actor, EDITORS);
        FaqItem before = find(id);
        jdbc.sql("DELETE FROM faq_items WHERE id = :id").param("id", id).update();
        auditLog.record(actor, "DELETE", ENTITY, id.toString(), before, null);
    }

    private FaqItem find(UUID id) {
        return jdbc.sql("SELECT * FROM faq_items WHERE id = :id").param("id", id).query(FaqItem.class).optional()
                .orElseThrow(() -> new ApiException("FAQ_NOT_FOUND", "Вопрос не найден", HttpStatus.NOT_FOUND));
    }
}
