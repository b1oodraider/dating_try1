package com.dating.social.persistence;

import com.dating.social.api.ModerationReportDto;
import com.dating.social.domain.ReportDecision;
import com.dating.social.domain.ReportReason;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ModerationRepository {

    private static final String SELECT = """
            SELECT r.id, r.status, r.reason, r.final_reason, r.decision, r.sanctioned, r.comment, r.message_id, r.message_snapshot,
                   r.reporter_account_id, r.reported_account_id, r.reported_profile_id, p.display_name,
                   (SELECT count(*) FROM reports c
                     WHERE c.reported_account_id = r.reported_account_id
                       AND c.decision IN ('ACCEPTED', 'RECLASSIFIED')) AS confirmed,
                   r.created_at, r.moderator_account_id, r.moderator_note, r.decided_at
              FROM reports r
              LEFT JOIN profiles p ON p.id = r.reported_profile_id
            """;

    private final JdbcClient jdbc;

    public ModerationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Oldest first: the queue is worked in arrival order. */
    public List<ModerationReportDto> page(Collection<String> statuses, int limit, int offset) {
        return jdbc.sql(SELECT + " WHERE r.status IN (:statuses) ORDER BY r.created_at, r.id LIMIT :limit OFFSET :offset")
                .param("statuses", statuses)
                .param("limit", limit)
                .param("offset", offset)
                .query(ModerationRepository::map)
                .list();
    }

    public long count(Collection<String> statuses) {
        return jdbc.sql("SELECT count(*) FROM reports WHERE status IN (:statuses)")
                .param("statuses", statuses)
                .query(Long.class)
                .single();
    }

    public Optional<ModerationReportDto> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(ModerationRepository::map).optional();
    }

    /** Locks the report so two moderators cannot decide it at the same time. */
    public Optional<String> lockDecision(UUID id) {
        return jdbc.sql("SELECT coalesce(decision, '') FROM reports WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    public void decide(UUID id, ReportDecision decision, String status, ReportReason finalReason, UUID moderator,
                       String note, Instant decidedAt) {
        jdbc.sql("""
                        UPDATE reports
                           SET decision = :decision, status = :status, final_reason = :finalReason,
                               moderator_account_id = :moderator, moderator_note = :note, decided_at = :decidedAt
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("decision", decision.name())
                .param("status", status)
                .param("finalReason", finalReason == null ? null : finalReason.name())
                .param("moderator", moderator)
                .param("note", note)
                .param("decidedAt", OffsetDateTime.ofInstant(decidedAt, ZoneOffset.UTC))
                .update();
    }

    private static ModerationReportDto map(ResultSet rs, int n) throws SQLException {
        return new ModerationReportDto(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                ReportReason.valueOf(rs.getString("reason")),
                enumOrNull(ReportReason.class, rs.getString("final_reason")),
                enumOrNull(ReportDecision.class, rs.getString("decision")),
                rs.getBoolean("sanctioned"),
                rs.getString("comment"),
                rs.getObject("message_id", UUID.class),
                rs.getString("message_snapshot"),
                rs.getObject("reporter_account_id", UUID.class),
                rs.getObject("reported_account_id", UUID.class),
                rs.getObject("reported_profile_id", UUID.class),
                rs.getString("display_name"),
                rs.getLong("confirmed"),
                instant(rs, "created_at"),
                rs.getObject("moderator_account_id", UUID.class),
                rs.getString("moderator_note"),
                instant(rs, "decided_at"));
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
