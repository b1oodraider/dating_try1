package com.dating.social.persistence;

import com.dating.social.api.ModerationReportDto;
import com.dating.social.domain.ReportDecision;
import com.dating.social.domain.ReportReason;
import com.dating.safety.persistence.RiskRepository;
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

    /** Wording that reads as a threat; such reports go to the top whatever their category. */
    static final String THREAT_SQL = "(убью|убить|прибью|зарежу|порежу|изнасил|сдохни|тебе конец|найду тебя|знаю,? где ты жив)";

    /**
     * Priority (block E of docs/trust-safety.md) = category base + 10 per extra person with an open report on the
     * same account (max 40) + risk / 4 (max 25) + 10 per confirmed violation (max 30) + 10 for contacts or links in
     * the reported message + 60 if the message or comment reads as a threat.
     */
    private static final String SELECT = """
            SELECT r.id, r.status, r.reason, r.final_reason, r.decision, r.sanctioned, r.auto_dismissed, r.comment,
                   r.message_id, r.message_snapshot, r.reporter_account_id, r.reported_account_id, r.reported_profile_id,
                   p.display_name, x.confirmed, x.risk, x.open_reporters, x.contacts, x.threat,
                   x.base + LEAST(40, 10 * GREATEST(0, x.open_reporters - 1)) + LEAST(25, coalesce(x.risk, 0) / 4)
                     + LEAST(30, 10 * x.confirmed) + CASE WHEN x.contacts THEN 10 ELSE 0 END
                     + CASE WHEN x.threat THEN 60 ELSE 0 END AS priority,
                   x.base, r.created_at, r.moderator_account_id, r.moderator_note, r.decided_at
              FROM reports r
              LEFT JOIN profiles p ON p.id = r.reported_profile_id
              CROSS JOIN LATERAL (
                SELECT CASE r.reason WHEN 'UNDERAGE' THEN 100 WHEN 'SCAM' THEN 80 WHEN 'HARASSMENT' THEN 70
                                     WHEN 'FAKE_PROFILE' THEN 50 WHEN 'INAPPROPRIATE_CONTENT' THEN 40 WHEN 'SPAM' THEN 30
                                     ELSE 20 END AS base,
                       (SELECT count(*) FROM reports c WHERE c.reported_account_id = r.reported_account_id
                          AND c.decision IN ('ACCEPTED', 'RECLASSIFIED')) AS confirmed,
                       (SELECT k.score FROM account_risk k WHERE k.account_id = r.reported_account_id) AS risk,
                       (SELECT count(DISTINCT o.reporter_account_id) FROM reports o
                         WHERE o.reported_account_id = r.reported_account_id AND o.status IN ('NEW', 'IN_REVIEW')) AS open_reporters,
                       coalesce(r.message_snapshot ~* '%s', false) AS contacts,
                       coalesce(r.message_snapshot ~* '%s', false) OR coalesce(r.comment ~* '%s', false) AS threat
              ) x
            """.formatted(RiskRepository.CONTACTS_SQL, THREAT_SQL, THREAT_SQL);

    private final JdbcClient jdbc;

    public ModerationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param byPriority most urgent first (then oldest); otherwise strictly oldest first */
    public List<ModerationReportDto> page(Collection<String> statuses, boolean byPriority, int limit, int offset) {
        return jdbc.sql(SELECT + " WHERE r.status IN (:statuses) ORDER BY "
                        + (byPriority ? "priority DESC, " : "") + "r.created_at, r.id LIMIT :limit OFFSET :offset")
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
                rs.getBoolean("auto_dismissed"),
                rs.getInt("priority"),
                priorityReasons(rs),
                rs.getString("comment"),
                rs.getObject("message_id", UUID.class),
                rs.getString("message_snapshot"),
                rs.getObject("reporter_account_id", UUID.class),
                rs.getObject("reported_account_id", UUID.class),
                rs.getObject("reported_profile_id", UUID.class),
                rs.getString("display_name"),
                rs.getLong("confirmed"),
                rs.getObject("risk", Integer.class),
                instant(rs, "created_at"),
                rs.getObject("moderator_account_id", UUID.class),
                rs.getString("moderator_note"),
                instant(rs, "decided_at"));
    }

    /** The same components as the priority expression, in words for the moderator. */
    private static List<String> priorityReasons(ResultSet rs) throws SQLException {
        List<String> reasons = new java.util.ArrayList<>();
        reasons.add("категория " + rs.getString("reason") + " (+" + rs.getInt("base") + ")");
        int reporters = rs.getInt("open_reporters");
        if (reporters > 1) {
            reasons.add("открытые жалобы от " + reporters + " человек (+" + Math.min(40, 10 * (reporters - 1)) + ")");
        }
        int risk = rs.getInt("risk");
        if (risk / 4 > 0) {
            reasons.add("риск-скор " + risk + " (+" + Math.min(25, risk / 4) + ")");
        }
        int confirmed = rs.getInt("confirmed");
        if (confirmed > 0) {
            reasons.add("подтверждённых нарушений: " + confirmed + " (+" + Math.min(30, 10 * confirmed) + ")");
        }
        if (rs.getBoolean("contacts")) {
            reasons.add("контакты или ссылки в сообщении (+10)");
        }
        if (rs.getBoolean("threat")) {
            reasons.add("похоже на угрозу (+60)");
        }
        return reasons;
    }

    /** Whether the reported person ever wrote to the reporter (in any of their chats). */
    public boolean wroteTo(UUID author, UUID reader) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM chat_messages c JOIN matches m ON m.id = c.match_id
                                        WHERE c.sender_account_id = :author
                                          AND (m.account_low = :reader OR m.account_high = :reader))
                        """)
                .param("author", author).param("reader", reader).query(Boolean.class).single();
    }

    /** Closes a report without a moderator (silence is not a violation). */
    public void autoDismiss(UUID id, String note) {
        jdbc.sql("""
                        UPDATE reports SET status = 'DISMISSED', decision = 'REJECTED', auto_dismissed = TRUE,
                               moderator_note = :note, decided_at = now()
                         WHERE id = :id
                        """)
                .param("id", id).param("note", note).update();
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
