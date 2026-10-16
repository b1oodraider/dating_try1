package com.dating.safety.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Raw behavior: reactions as they happened and profile views reported by the client. */
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

    public int deleteOlderThan(Instant before) {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(before, ZoneOffset.UTC);
        return jdbc.sql("DELETE FROM reaction_events WHERE reacted_at < :before").param("before", cutoff).update()
                + jdbc.sql("DELETE FROM profile_views WHERE viewed_at < :before").param("before", cutoff).update();
    }

    public void deleteAccountData(UUID accountId) {
        jdbc.sql("DELETE FROM reaction_events WHERE actor_account_id = :id OR target_account_id = :id")
                .param("id", accountId).update();
        jdbc.sql("DELETE FROM profile_views WHERE viewer_account_id = :id OR target_account_id = :id")
                .param("id", accountId).update();
    }
}
