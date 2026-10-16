package com.dating.safety.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Raw bot / fake signals of one account. Each query answers one question; weights live in RiskService.
 * shortcut: the duplicate-photo and duplicate-text checks scan all active rows; fine for tens of thousands of
 * profiles, needs an index-friendly approach (hash buckets) beyond that.
 */
@Repository
public class RiskRepository {

    /** Links, messenger names, @handles, phone numbers. Same pattern as {@link #CONTACTS_JAVA}. */
    public static final String CONTACTS_SQL =
            "(https?://|www\\.|t\\.me/|telegram|телеграм|whats ?app|ватсап|вотсап|instagram|инстаграм|"
                    + "@[a-z0-9_]{4,}|\\+?[0-9][0-9 ()-]{9,}[0-9])";
    public static final java.util.regex.Pattern CONTACTS_JAVA = java.util.regex.Pattern.compile(CONTACTS_SQL,
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final String NORMALIZED_TEXT = "lower(regexp_replace(btrim(%s), '\\s+', ' ', 'g'))";

    public record StoredRisk(UUID accountId, int score, String level, String signalsJson, Instant computedAt,
                             UUID profileId, String displayName) {
    }

    private final JdbcClient jdbc;

    public RiskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void recordSignup(UUID accountId, String ipHash) {
        jdbc.sql("INSERT INTO account_signups (account_id, ip_hash) VALUES (:account, :ip) ON CONFLICT DO NOTHING")
                .param("account", accountId).param("ip", ipHash).update();
    }

    /** Accounts (this one included) registered from the same address within a day of this one. */
    public int signupsFromSameAddress(UUID accountId) {
        return count("""
                SELECT count(*) FROM account_signups s JOIN account_signups me ON me.ip_hash = s.ip_hash
                 WHERE me.account_id = :account
                   AND s.created_at BETWEEN me.created_at - interval '1 day' AND me.created_at + interval '1 day'
                """, accountId);
    }

    /** Other accounts showing the same picture (identical bytes or a near-identical difference hash). */
    public int accountsWithSamePhoto(UUID accountId) {
        return count("""
                SELECT count(DISTINCT op.account_id)
                  FROM profile_photos mp
                  JOIN profiles me ON me.id = mp.profile_id AND me.account_id = :account
                  JOIN profile_photos o ON o.deleted_at IS NULL AND o.profile_id <> mp.profile_id
                       AND (o.content_sha256 = mp.content_sha256
                            OR (o.dhash IS NOT NULL AND mp.dhash IS NOT NULL
                                AND bit_count((o.dhash # mp.dhash)::bit(64)) <= 6))
                  JOIN profiles op ON op.id = o.profile_id
                 WHERE mp.deleted_at IS NULL
                """, accountId);
    }

    /** Other profiles with the same "about me" (at least 30 characters, ignoring case and spacing). */
    public int profilesWithSameAbout(UUID accountId) {
        return count(("""
                SELECT count(*) FROM profiles o JOIN profiles me ON me.account_id = :account
                 WHERE o.id <> me.id AND length(btrim(me.about_text)) >= 30
                   AND %s = %s
                """).formatted(NORMALIZED_TEXT.formatted("o.about_text"), NORMALIZED_TEXT.formatted("me.about_text")),
                accountId);
    }

    public boolean contactsInProfile(UUID accountId) {
        return count("SELECT count(*) FROM profiles WHERE account_id = :account AND (about_text ~* '" + CONTACTS_SQL
                + "' OR daily_status_text ~* '" + CONTACTS_SQL + "')", accountId) > 0;
    }

    /** Largest number of different chats that got the same text from this account within the period. */
    public int sameTextToChats(UUID accountId, Instant since) {
        return jdbc.sql(("""
                        SELECT coalesce(max(n), 0) FROM (
                          SELECT count(DISTINCT match_id) AS n FROM chat_messages
                           WHERE sender_account_id = :account AND type = 'TEXT' AND created_at > :since
                           GROUP BY %s) t
                        """).formatted(NORMALIZED_TEXT.formatted("text")))
                .param("account", accountId).param("since", odt(since)).query(Integer.class).single();
    }

    /** Different chats that got this exact text (normalized) from the account within the period. */
    public int chatsWithText(UUID accountId, String text, Instant since) {
        return jdbc.sql(("""
                        SELECT count(DISTINCT match_id) FROM chat_messages
                         WHERE sender_account_id = :account AND type = 'TEXT' AND created_at > :since AND %s = %s
                        """).formatted(NORMALIZED_TEXT.formatted("text"), NORMALIZED_TEXT.formatted("CAST(:text AS text)")))
                .param("account", accountId).param("since", odt(since)).param("text", text)
                .query(Integer.class).single();
    }

    public int chatsWithContacts(UUID accountId, Instant since) {
        return jdbc.sql("SELECT count(DISTINCT match_id) FROM chat_messages WHERE sender_account_id = :account"
                        + " AND type = 'TEXT' AND created_at > :since AND text ~* '" + CONTACTS_SQL + "'")
                .param("account", accountId).param("since", odt(since)).query(Integer.class).single();
    }

    public int ruleRestrictions(UUID accountId, String reason, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM account_restrictions
                         WHERE account_id = :account AND source = 'RULE' AND reason = :reason AND starts_at > :since
                        """)
                .param("account", accountId).param("reason", reason).param("since", odt(since))
                .query(Integer.class).single();
    }

    /** Different people who reported the account within the period (any reason, or only the given ones). */
    public int reporters(UUID accountId, Instant since, List<String> reasons) {
        return jdbc.sql("SELECT count(DISTINCT reporter_account_id) FROM reports WHERE reported_account_id = :account"
                        + " AND created_at > :since AND NOT auto_dismissed"
                        + (reasons == null ? "" : " AND reason IN (:reasons)"))
                .param("account", accountId).param("since", odt(since))
                .param("reasons", reasons == null ? List.of("") : reasons)
                .query(Integer.class).single();
    }

    public Optional<Instant> accountCreatedAt(UUID accountId) {
        return jdbc.sql("SELECT created_at FROM accounts WHERE id = :account").param("account", accountId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant()).optional();
    }

    public void save(UUID accountId, int score, String level, String signalsJson) {
        jdbc.sql("""
                        INSERT INTO account_risk (account_id, score, level, signals, computed_at)
                        VALUES (:account, :score, :level, CAST(:signals AS jsonb), now())
                        ON CONFLICT (account_id) DO UPDATE SET score = EXCLUDED.score, level = EXCLUDED.level,
                            signals = EXCLUDED.signals, computed_at = EXCLUDED.computed_at
                        """)
                .param("account", accountId).param("score", score).param("level", level).param("signals", signalsJson)
                .update();
    }

    /** Riskiest first; only accounts that can still do harm (ACTIVE). */
    public List<StoredRisk> queue(int minScore, int limit, int offset) {
        return jdbc.sql("""
                        SELECT r.account_id, r.score, r.level, r.signals::text, r.computed_at, p.id, p.display_name
                          FROM account_risk r
                          JOIN accounts a ON a.id = r.account_id AND a.status = 'ACTIVE'
                          LEFT JOIN profiles p ON p.account_id = r.account_id
                         WHERE r.score >= :min
                         ORDER BY r.score DESC, r.computed_at DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("min", minScore).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new StoredRisk(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class).toInstant(), rs.getObject(6, UUID.class),
                        rs.getString(7)))
                .list();
    }

    public long queueSize(int minScore) {
        return jdbc.sql("""
                        SELECT count(*) FROM account_risk r JOIN accounts a ON a.id = r.account_id AND a.status = 'ACTIVE'
                         WHERE r.score >= :min
                        """)
                .param("min", minScore).query(Long.class).single();
    }

    public void deleteAccountData(UUID accountId) {
        jdbc.sql("DELETE FROM account_risk WHERE account_id = :account").param("account", accountId).update();
        jdbc.sql("DELETE FROM account_signups WHERE account_id = :account").param("account", accountId).update();
    }

    private int count(String sql, UUID accountId) {
        return jdbc.sql(sql).param("account", accountId).query(Integer.class).single();
    }

    private static OffsetDateTime odt(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }
}
