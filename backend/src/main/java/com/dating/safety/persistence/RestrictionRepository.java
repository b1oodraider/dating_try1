package com.dating.safety.persistence;

import com.dating.safety.domain.Restriction;
import com.dating.safety.domain.RestrictionKind;
import com.dating.safety.domain.SanctionPolicy;
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
import java.util.Optional;
import java.util.UUID;

@Repository
public class RestrictionRepository {

    private static final TypeReference<List<SanctionPolicy.Step>> STEPS = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, account_id, kind, reason, source, report_id, created_by, note, starts_at, ends_at,
            lifted_at, lifted_by, lift_note
            """;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public RestrictionRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    // ---------------------------------------------------------------- restrictions

    /**
     * Starts at the database's now(): within a transaction that is its start time, so the new restriction is
     * already "active" for the queries of the same transaction.
     *
     * @param hours null = until lifted
     */
    public Restriction insert(UUID accountId, RestrictionKind kind, String reason, String source, UUID reportId,
                              UUID createdBy, String note, Integer hours) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO account_restrictions (id, account_id, kind, reason, source, report_id, created_by, note,
                                                          starts_at, ends_at)
                        VALUES (:id, :account, :kind, :reason, :source, :report, :createdBy, :note, now(),
                                now() + make_interval(hours => CAST(:hours AS int)))
                        """)
                .param("id", id)
                .param("account", accountId)
                .param("kind", kind.name())
                .param("reason", reason)
                .param("source", source)
                .param("report", reportId)
                .param("createdBy", createdBy)
                .param("note", note)
                .param("hours", hours)
                .update();
        return find(id).orElseThrow();
    }

    public Optional<Restriction> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account_restrictions WHERE id = :id")
                .param("id", id).query(RestrictionRepository::map).optional();
    }

    /** Not lifted and not ended yet (ends_at is checked here too: the expiry job may lag a little). */
    public List<Restriction> active(UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM account_restrictions
                        WHERE account_id = :account AND lifted_at IS NULL AND starts_at <= now()
                          AND (ends_at IS NULL OR ends_at > now())
                        ORDER BY starts_at
                        """)
                .param("account", accountId).query(RestrictionRepository::map).list();
    }

    /** Newest first, lifted and expired included. */
    public List<Restriction> history(UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account_restrictions WHERE account_id = :account ORDER BY starts_at DESC")
                .param("account", accountId).query(RestrictionRepository::map).list();
    }

    public boolean lift(UUID id, UUID liftedBy, String note) {
        return jdbc.sql("""
                        UPDATE account_restrictions SET lifted_at = now(), lifted_by = :by, lift_note = :note
                         WHERE id = :id AND lifted_at IS NULL
                        """)
                .param("id", id).param("by", liftedBy).param("note", note).update() == 1;
    }

    /** Closes ended restrictions; returns the accounts whose derived state must be recomputed. */
    public List<UUID> expireEnded() {
        return jdbc.sql("""
                        UPDATE account_restrictions SET lifted_at = ends_at, lift_note = 'expired'
                         WHERE lifted_at IS NULL AND ends_at <= now()
                        RETURNING account_id
                        """)
                .query(UUID.class).list().stream().distinct().toList();
    }

    /** Serializes moderation of one account (parallel decisions must not double-escalate). */
    public void lockAccount(UUID accountId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('sanctions:' || :account, 0))")
                .param("account", accountId.toString())
                .query((rs, n) -> 1)
                .list();
    }

    // ---------------------------------------------------------------- reports (escalation and holds)

    /** Earlier sanctioned violations of the reason, i.e. the escalation step of the next one. */
    public int sanctionedViolations(UUID accountId, String reason, Instant since, UUID excludeReportId) {
        return jdbc.sql("""
                        SELECT count(*) FROM reports
                         WHERE reported_account_id = :account AND sanctioned AND final_reason = :reason
                           AND decided_at > :since AND id <> :exclude
                        """)
                .param("account", accountId).param("reason", reason).param("since", odt(since))
                .param("exclude", excludeReportId)
                .query(Integer.class).single();
    }

    /** Restrictions a rule gave the account since the given time (escalation of rule-based sanctions). */
    public int ruleRestrictions(UUID accountId, String reason, RestrictionKind kind, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM account_restrictions
                         WHERE account_id = :account AND source = 'RULE' AND reason = :reason AND kind = :kind
                           AND starts_at > :since
                        """)
                .param("account", accountId).param("reason", reason).param("kind", kind.name())
                .param("since", odt(since))
                .query(Integer.class).single();
    }

    public void markSanctioned(UUID reportId) {
        jdbc.sql("UPDATE reports SET sanctioned = TRUE WHERE id = :id").param("id", reportId).update();
    }

    /** Different people with open reports of this reason against the account. */
    public int openReporters(UUID accountId, String reason) {
        return jdbc.sql("""
                        SELECT count(DISTINCT reporter_account_id) FROM reports
                         WHERE reported_account_id = :account AND reason = :reason AND status IN ('NEW', 'IN_REVIEW')
                        """)
                .param("account", accountId).param("reason", reason)
                .query(Integer.class).single();
    }

    // ---------------------------------------------------------------- derived state

    public void setAccountStatus(UUID accountId, String from, String to) {
        jdbc.sql("UPDATE accounts SET status = :to, updated_at = now() WHERE id = :id AND status = :from")
                .param("id", accountId).param("from", from).param("to", to).update();
    }

    public void setProfileVisibility(UUID accountId, String visibility) {
        jdbc.sql("""
                        UPDATE profiles SET visibility_status = :visibility, updated_at = now()
                         WHERE account_id = :account AND visibility_status <> :visibility
                        """)
                .param("account", accountId).param("visibility", visibility).update();
    }

    // ---------------------------------------------------------------- policy

    public List<SanctionPolicy> policies() {
        return jdbc.sql("SELECT reason, window_days, hold_on_reports, steps::text FROM sanction_policy ORDER BY reason")
                .query((rs, n) -> policy(rs)).list();
    }

    public Optional<SanctionPolicy> policy(String reason) {
        return jdbc.sql("SELECT reason, window_days, hold_on_reports, steps::text FROM sanction_policy WHERE reason = :reason")
                .param("reason", reason).query((rs, n) -> policy(rs)).optional();
    }

    public void savePolicy(SanctionPolicy policy, UUID updatedBy) {
        jdbc.sql("""
                        INSERT INTO sanction_policy (reason, window_days, hold_on_reports, steps, updated_by)
                        VALUES (:reason, :window, :hold, CAST(:steps AS jsonb), :by)
                        ON CONFLICT (reason) DO UPDATE SET window_days = EXCLUDED.window_days,
                            hold_on_reports = EXCLUDED.hold_on_reports, steps = EXCLUDED.steps,
                            updated_by = EXCLUDED.updated_by, updated_at = now()
                        """)
                .param("reason", policy.reason())
                .param("window", policy.windowDays())
                .param("hold", policy.holdOnReports())
                .param("steps", jsonMapper.writeValueAsString(policy.steps()))
                .param("by", updatedBy)
                .update();
    }

    private SanctionPolicy policy(ResultSet rs) throws SQLException {
        Integer hold = rs.getObject(3, Integer.class);
        return new SanctionPolicy(rs.getString(1), rs.getInt(2), hold, jsonMapper.readValue(rs.getString(4), STEPS));
    }

    private static Restriction map(ResultSet rs, int n) throws SQLException {
        return new Restriction(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                RestrictionKind.valueOf(rs.getString("kind")),
                rs.getString("reason"),
                rs.getString("source"),
                rs.getObject("report_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                rs.getString("note"),
                instant(rs, "starts_at"),
                instant(rs, "ends_at"),
                instant(rs, "lifted_at"),
                rs.getObject("lifted_by", UUID.class),
                rs.getString("lift_note"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
