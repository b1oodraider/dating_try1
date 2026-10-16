package com.dating.safety.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Raw behavior (reactions, client views, feed impressions, active days) and the metrics computed from it. */
@Repository
public class BehaviorRepository {

    /**
     * @param lookedMs   longest look at the target before the reaction (null = the client sent no view)
     * @param opened     the details were opened in any of those views
     */
    public record ReactionEvent(UUID target, String type, Instant at, Integer lookedMs, boolean opened) {
        public boolean isLike() {
            return !"PASS".equals(type);
        }
    }

    public record View(UUID profileId, int dwellMs, boolean openedDetails) {
    }

    private final JdbcClient jdbc;

    public BehaviorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void recordReaction(UUID actor, UUID target, String type) {
        jdbc.sql("INSERT INTO reaction_events (actor_account_id, target_account_id, type) VALUES (:actor, :target, :type)")
                .param("actor", actor).param("target", target).param("type", type).update();
    }

    /** Views of profiles that exist; unknown profile ids are skipped. */
    public void recordViews(UUID viewer, List<View> views) {
        for (View view : views) {
            jdbc.sql("""
                            INSERT INTO profile_views (viewer_account_id, target_account_id, dwell_ms, opened_details)
                            SELECT :viewer, p.account_id, :dwell, :opened FROM profiles p
                             WHERE p.id = :profile AND p.account_id <> :viewer
                            """)
                    .param("viewer", viewer).param("profile", view.profileId())
                    .param("dwell", view.dwellMs()).param("opened", view.openedDetails())
                    .update();
        }
    }

    /** Newest first, with what the client said about looking at each target before the reaction. */
    public List<ReactionEvent> recentReactions(UUID actor, int limit, Instant since) {
        return jdbc.sql("""
                        SELECT e.target_account_id, e.type, e.reacted_at,
                               (SELECT max(v.dwell_ms) FROM profile_views v
                                 WHERE v.viewer_account_id = e.actor_account_id AND v.target_account_id = e.target_account_id
                                   AND v.viewed_at <= e.reacted_at) AS looked,
                               EXISTS (SELECT 1 FROM profile_views v
                                 WHERE v.viewer_account_id = e.actor_account_id AND v.target_account_id = e.target_account_id
                                   AND v.viewed_at <= e.reacted_at AND v.opened_details) AS opened
                          FROM reaction_events e
                         WHERE e.actor_account_id = :actor AND e.reacted_at > :since
                         ORDER BY e.reacted_at DESC, e.id DESC
                         LIMIT :limit
                        """)
                .param("actor", actor).param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC)).param("limit", limit)
                .query((rs, n) -> new ReactionEvent(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getObject(4, Integer.class), rs.getBoolean(5)))
                .list();
    }

    public void recordImpressions(UUID viewer, Collection<UUID> targets) {
        if (targets.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO feed_impressions (viewer_account_id, target_account_id)
                        SELECT :viewer, a.id FROM accounts a WHERE a.id IN (:targets) AND a.id <> :viewer
                        """)
                .param("viewer", viewer).param("targets", targets).update();
    }

    public void recordActiveDay(UUID accountId, LocalDate day) {
        jdbc.sql("INSERT INTO account_activity_days (account_id, day) VALUES (:account, :day) ON CONFLICT DO NOTHING")
                .param("account", accountId).param("day", day).update();
    }

    /** Accounts seen within the period: the ones whose metrics are worth recomputing. */
    public List<UUID> activeSince(LocalDate since) {
        return jdbc.sql("SELECT DISTINCT account_id FROM account_activity_days WHERE day >= :since")
                .param("since", since).query(UUID.class).list();
    }

    /**
     * Computes and stores the metrics of one account (docs/trust-safety.md, block D). Message text is only measured
     * here (length, one word or not) and never leaves the database. Rates are null when there is nothing to divide.
     *
     * @return the metrics as JSON
     */
    public String computeMetrics(UUID accountId) {
        return jdbc.sql(METRICS_SQL).param("account", accountId).query(String.class).single();
    }

    public Optional<StoredMetrics> metrics(UUID accountId) {
        return jdbc.sql("SELECT computed_at, metrics::text FROM behavior_metrics WHERE account_id = :account")
                .param("account", accountId)
                .query((rs, n) -> new StoredMetrics(rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getString(2)))
                .optional();
    }

    public record StoredMetrics(Instant computedAt, String json) {
    }

    private static final String METRICS_SQL = """
            WITH r AS (
                SELECT type, reacted_at, lag(reacted_at) OVER (ORDER BY reacted_at, id) AS prev
                  FROM reaction_events
                 WHERE actor_account_id = :account AND reacted_at > now() - interval '30 days'
            ), gaps AS (
                SELECT extract(epoch FROM reacted_at - prev) * 1000 AS ms FROM r
                 WHERE prev IS NOT NULL AND reacted_at - prev < interval '10 minutes'
            ), m AS (
                SELECT id FROM matches
                 WHERE (account_low = :account OR account_high = :account) AND created_at > now() - interval '90 days'
            ), msg AS (
                SELECT c.match_id, c.sender_account_id = :account AS mine, c.type, c.text, c.created_at
                  FROM chat_messages c JOIN m ON m.id = c.match_id
            ), d AS (
                SELECT match_id, bool_or(mine) AS i_wrote, bool_or(NOT mine) AS they_wrote,
                       (array_agg(mine ORDER BY created_at))[1] AS first_mine,
                       min(created_at) FILTER (WHERE NOT mine) AS their_first
                  FROM msg GROUP BY match_id
            ), reply AS (
                SELECT (SELECT min(x.created_at) FROM msg x
                         WHERE x.match_id = d.match_id AND x.mine AND x.created_at > d.their_first) - d.their_first AS delay
                  FROM d WHERE NOT d.first_mine
            ), texts AS (
                SELECT length(btrim(text)) AS len, strpos(btrim(text), ' ') = 0 AS one_word
                  FROM msg WHERE mine AND type = 'TEXT'
            ), c AS (
                SELECT
                  (SELECT count(*) FROM feed_impressions
                    WHERE viewer_account_id = :account AND shown_at > now() - interval '30 days') AS seen,
                  (SELECT count(*) FROM r WHERE type <> 'PASS') AS likes,
                  (SELECT count(*) FROM r WHERE type = 'SUPERLIKE') AS superlikes,
                  (SELECT count(*) FROM r WHERE type = 'PASS') AS passes,
                  (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY ms) FROM gaps) AS swipe_ms,
                  (SELECT count(*) FROM feed_impressions
                    WHERE target_account_id = :account AND shown_at > now() - interval '30 days') AS shown,
                  (SELECT count(*) FROM reaction_events WHERE target_account_id = :account AND type <> 'PASS'
                      AND reacted_at > now() - interval '30 days') AS likes_in,
                  (SELECT count(*) FROM m) AS matches,
                  (SELECT count(*) FROM d) AS dialogs,
                  (SELECT count(*) FROM d WHERE NOT first_mine) AS opened_by_them,
                  (SELECT count(*) FROM reply WHERE delay IS NOT NULL) AS replied,
                  (SELECT count(*) FROM d WHERE they_wrote) AS they_wrote,
                  (SELECT count(*) FROM d WHERE they_wrote AND NOT i_wrote) AS ghosted,
                  (SELECT count(*) FROM d WHERE first_mine) AS initiated,
                  (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM delay))
                     FROM reply WHERE delay IS NOT NULL) AS response_s,
                  (SELECT count(*) FROM msg WHERE mine) AS sent,
                  (SELECT avg(len) FROM texts) AS avg_len,
                  (SELECT count(*) FROM texts WHERE one_word) AS one_word,
                  (SELECT count(*) FROM texts) AS text_count,
                  (SELECT count(*) FROM reports WHERE reported_account_id = :account) AS reports_in,
                  (SELECT count(*) FROM reports WHERE reporter_account_id = :account) AS reports_out,
                  (SELECT count(*) FROM reports WHERE reported_account_id = :account
                      AND decision IN ('ACCEPTED', 'RECLASSIFIED')) AS violations,
                  (SELECT count(*) FROM account_blocks WHERE blocked_account_id = :account) AS blocked_by,
                  (SELECT count(*) FROM account_activity_days
                    WHERE account_id = :account AND day > current_date - 30) AS active_days
            ), metrics AS (
                SELECT jsonb_build_object(
                  'likesGiven30d', likes, 'superlikesGiven30d', superlikes, 'passesGiven30d', passes,
                  'profilesSeen30d', seen,
                  'likeRatio', round(likes::numeric / NULLIF(likes + passes, 0), 3),
                  'medianSwipeMs', round(swipe_ms::numeric),
                  'timesShown30d', shown, 'likesReceived30d', likes_in,
                  'inboundLikeRate', round(likes_in::numeric / NULLIF(shown, 0), 3),
                  'matches90d', matches, 'dialogs90d', dialogs, 'silentMatches90d', matches - dialogs,
                  'responseRate', round(replied::numeric / NULLIF(opened_by_them, 0), 3),
                  'ghostRate', round(ghosted::numeric / NULLIF(they_wrote, 0), 3),
                  'initiationRate', round(initiated::numeric / NULLIF(dialogs, 0), 3),
                  'medianResponseSeconds', round(response_s::numeric),
                  'messagesSent90d', sent,
                  'avgMessageLength', round(avg_len, 1),
                  'oneWordShare', round(one_word::numeric / NULLIF(text_count, 0), 3),
                  'reportsReceived', reports_in, 'reportsMade', reports_out, 'confirmedViolations', violations,
                  'blockedBy', blocked_by, 'activeDays30d', active_days) AS json
                  FROM c
            ), saved AS (
                INSERT INTO behavior_metrics (account_id, computed_at, metrics)
                SELECT :account, now(), json FROM metrics
                ON CONFLICT (account_id) DO UPDATE SET computed_at = EXCLUDED.computed_at, metrics = EXCLUDED.metrics
            )
            SELECT json::text FROM metrics
            """;

    public int deleteOlderThan(Instant before) {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(before, ZoneOffset.UTC);
        return jdbc.sql("DELETE FROM reaction_events WHERE reacted_at < :before").param("before", cutoff).update()
                + jdbc.sql("DELETE FROM profile_views WHERE viewed_at < :before").param("before", cutoff).update()
                + jdbc.sql("DELETE FROM feed_impressions WHERE shown_at < :before").param("before", cutoff).update()
                + jdbc.sql("DELETE FROM account_activity_days WHERE day < :before")
                        .param("before", cutoff.toLocalDate()).update();
    }

    public void deleteAccountData(UUID accountId) {
        jdbc.sql("DELETE FROM reaction_events WHERE actor_account_id = :id OR target_account_id = :id")
                .param("id", accountId).update();
        jdbc.sql("DELETE FROM profile_views WHERE viewer_account_id = :id OR target_account_id = :id")
                .param("id", accountId).update();
        jdbc.sql("DELETE FROM feed_impressions WHERE viewer_account_id = :id OR target_account_id = :id")
                .param("id", accountId).update();
        jdbc.sql("DELETE FROM account_activity_days WHERE account_id = :id").param("id", accountId).update();
        jdbc.sql("DELETE FROM behavior_metrics WHERE account_id = :id").param("id", accountId).update();
    }
}
