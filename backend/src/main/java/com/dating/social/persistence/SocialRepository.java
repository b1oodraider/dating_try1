package com.dating.social.persistence;

import com.dating.social.domain.MatchInfo;
import com.dating.social.domain.ReactionType;
import com.dating.social.domain.ReportReason;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reactions, matches, blocks and reports. Plain SQL (like the economy module): idempotent upserts and
 * race-safe match creation are single statements. Runs inside the surrounding Spring transaction.
 * A pair of accounts is always stored / looked up as (LEAST, GREATEST) so ordering matches PostgreSQL's.
 */
@Repository
public class SocialRepository {

    public record ReceivedLikeRow(UUID actorAccountId, boolean superlike, Instant likedAt) {
    }

    public record ReceivedLikesCount(long count, long superlikeCount) {
    }

    private static final String MATCH_COLUMNS = "m.id, m.account_low, m.account_high, m.created_at, m.closed_at";

    /** Likes that are still waiting for my answer: the liker is visible and I have not liked / passed / blocked. */
    private static final String RECEIVED_LIKES_WHERE = """
            FROM profile_reactions r
            JOIN accounts a ON a.id = r.actor_account_id AND a.status = 'ACTIVE'
            JOIN profiles p ON p.account_id = r.actor_account_id
                           AND p.publication_status = 'PUBLISHED' AND p.visibility_status = 'PUBLIC'
            WHERE r.target_account_id = :me AND r.type IN ('LIKE', 'SUPERLIKE')
              AND NOT social_hides_profile(:me, r.actor_account_id, :passTtl)
            """;

    private final JdbcClient jdbc;

    public SocialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- locking

    /**
     * Serializes everything that happens to one pair of accounts until the end of the transaction
     * (mutual likes, likes vs block). Collisions of the hash only cause extra waiting, never errors.
     */
    public void lockPair(UUID a, UUID b) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(LEAST(:a, :b)::text || GREATEST(:a, :b)::text, 12))")
                .param("a", a)
                .param("b", b)
                .query((rs, n) -> 1)
                .list();
    }

    // ---------------------------------------------------------------- reactions

    public Optional<ReactionType> findReaction(UUID actor, UUID target) {
        return jdbc.sql("SELECT type FROM profile_reactions WHERE actor_account_id = :actor AND target_account_id = :target")
                .param("actor", actor)
                .param("target", target)
                .query((rs, n) -> ReactionType.valueOf(rs.getString(1)))
                .optional();
    }

    public void upsertReaction(UUID actor, UUID target, ReactionType type) {
        jdbc.sql("""
                        INSERT INTO profile_reactions (actor_account_id, target_account_id, type)
                        VALUES (:actor, :target, :type)
                        ON CONFLICT (actor_account_id, target_account_id)
                        DO UPDATE SET type = EXCLUDED.type, reacted_at = now()
                        """)
                .param("actor", actor)
                .param("target", target)
                .param("type", type.name())
                .update();
    }

    /**
     * @param passesOnly true: only PASS rows (the feed shows those profiles again)
     */
    public int deleteReactionsOf(UUID actor, boolean passesOnly) {
        return jdbc.sql("DELETE FROM profile_reactions WHERE actor_account_id = :actor"
                        + (passesOnly ? " AND type = 'PASS'" : ""))
                .param("actor", actor)
                .update();
    }

    public List<ReceivedLikeRow> receivedLikes(UUID me, Duration passTtl, int limit, long offset) {
        return jdbc.sql("SELECT r.actor_account_id, r.type = 'SUPERLIKE', r.reacted_at " + RECEIVED_LIKES_WHERE + """
                        ORDER BY r.type = 'SUPERLIKE' DESC, r.reacted_at DESC, r.actor_account_id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("me", me)
                .param("passTtl", passTtl.toSeconds())
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new ReceivedLikeRow(rs.getObject(1, UUID.class), rs.getBoolean(2), instant(rs, 3)))
                .list();
    }

    public ReceivedLikesCount countReceivedLikes(UUID me, Duration passTtl) {
        return jdbc.sql("SELECT count(*), count(*) FILTER (WHERE r.type = 'SUPERLIKE') " + RECEIVED_LIKES_WHERE)
                .param("me", me)
                .param("passTtl", passTtl.toSeconds())
                .query((rs, n) -> new ReceivedLikesCount(rs.getLong(1), rs.getLong(2)))
                .single();
    }

    // ---------------------------------------------------------------- matches

    /**
     * @return true if created; false if the pair already has an active match (concurrent mutual likes)
     */
    public boolean insertMatch(UUID id, UUID a, UUID b) {
        return jdbc.sql("""
                        INSERT INTO matches (id, account_low, account_high)
                        VALUES (:id, LEAST(:a, :b), GREATEST(:a, :b))
                        ON CONFLICT (account_low, account_high) WHERE closed_at IS NULL DO NOTHING
                        """)
                .param("id", id)
                .param("a", a)
                .param("b", b)
                .update() == 1;
    }

    public Optional<MatchInfo> findActiveMatchBetween(UUID a, UUID b) {
        return jdbc.sql("SELECT " + MATCH_COLUMNS + """
                         FROM matches m
                        WHERE m.account_low = LEAST(:a, :b) AND m.account_high = GREATEST(:a, :b) AND m.closed_at IS NULL
                        """)
                .param("a", a)
                .param("b", b)
                .query(SocialRepository::match)
                .optional();
    }

    public Optional<MatchInfo> findMatch(UUID matchId) {
        return jdbc.sql("SELECT " + MATCH_COLUMNS + " FROM matches m WHERE m.id = :id")
                .param("id", matchId)
                .query(SocialRepository::match)
                .optional();
    }

    /**
     * Active matches of the account whose other side is still an active account.
     */
    public List<MatchInfo> activeMatchesOf(UUID me) {
        return jdbc.sql("SELECT " + MATCH_COLUMNS + """
                         FROM matches m
                        JOIN accounts a ON a.id = CASE WHEN m.account_low = :me THEN m.account_high ELSE m.account_low END
                        WHERE (m.account_low = :me OR m.account_high = :me) AND m.closed_at IS NULL AND a.status = 'ACTIVE'
                        ORDER BY m.created_at DESC
                        """)
                .param("me", me)
                .query(SocialRepository::match)
                .list();
    }

    /**
     * All active matches involving the account, regardless of the other side's status (account deletion).
     */
    public List<MatchInfo> activeMatchesInvolving(UUID accountId) {
        return jdbc.sql("SELECT " + MATCH_COLUMNS + """
                         FROM matches m
                        WHERE (m.account_low = :id OR m.account_high = :id) AND m.closed_at IS NULL
                        """)
                .param("id", accountId)
                .query(SocialRepository::match)
                .list();
    }

    /**
     * @return true if the match was active and is closed now
     */
    public boolean closeMatch(UUID matchId, UUID closedBy, String reason) {
        return jdbc.sql("""
                        UPDATE matches SET closed_at = now(), closed_by = :by, close_reason = :reason
                        WHERE id = :id AND closed_at IS NULL
                        """)
                .param("id", matchId)
                .param("by", closedBy)
                .param("reason", reason)
                .update() == 1;
    }

    // ---------------------------------------------------------------- blocks

    public void insertBlock(UUID blocker, UUID blocked) {
        jdbc.sql("""
                        INSERT INTO account_blocks (blocker_account_id, blocked_account_id) VALUES (:blocker, :blocked)
                        ON CONFLICT DO NOTHING
                        """)
                .param("blocker", blocker)
                .param("blocked", blocked)
                .update();
    }

    public boolean isBlockedEitherWay(UUID a, UUID b) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM account_blocks
                                       WHERE (blocker_account_id = :a AND blocked_account_id = :b)
                                          OR (blocker_account_id = :b AND blocked_account_id = :a))
                        """)
                .param("a", a)
                .param("b", b)
                .query(Boolean.class)
                .single();
    }

    // ---------------------------------------------------------------- reports

    public void insertReport(UUID id, UUID reporter, UUID reported, UUID reportedProfileId, UUID messageId,
                             String messageSnapshot, ReportReason reason, String comment) {
        jdbc.sql("""
                        INSERT INTO reports (id, reporter_account_id, reported_account_id, reported_profile_id,
                                             message_id, message_snapshot, reason, comment)
                        VALUES (:id, :reporter, :reported, :profileId, :messageId, :snapshot, :reason, :comment)
                        """)
                .param("id", id)
                .param("reporter", reporter)
                .param("reported", reported)
                .param("profileId", reportedProfileId)
                .param("messageId", messageId)
                .param("snapshot", messageSnapshot)
                .param("reason", reason.name())
                .param("comment", comment)
                .update();
    }

    // ---------------------------------------------------------------- account deletion

    /**
     * Removes everything social about the account: reports it made, its matches (messages and read
     * state go with them, ON DELETE CASCADE), reactions and blocks in both directions.
     * Reports made by others about this account are kept for moderation.
     */
    public void deleteAccountData(UUID accountId) {
        jdbc.sql("DELETE FROM reports WHERE reporter_account_id = :id").param("id", accountId).update();
        jdbc.sql("DELETE FROM matches WHERE account_low = :id OR account_high = :id").param("id", accountId).update();
        jdbc.sql("DELETE FROM profile_reactions WHERE actor_account_id = :id OR target_account_id = :id")
                .param("id", accountId).update();
        jdbc.sql("DELETE FROM account_blocks WHERE blocker_account_id = :id OR blocked_account_id = :id")
                .param("id", accountId).update();
    }

    // ---------------------------------------------------------------- mapping

    private static MatchInfo match(ResultSet rs, int rowNum) throws SQLException {
        return new MatchInfo(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                instant(rs, 4), instant(rs, 5));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
