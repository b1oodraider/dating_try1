package com.dating.boost.persistence;

import com.dating.boost.domain.Boost;
import com.dating.boost.domain.BoostSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Boost storage. Plain SQL: impressions are bumped for a whole discovery page in one statement.
 * "Active" in SQL uses boost_owner_now(account_id) (migration 011): the owner's clock, like in the API.
 */
@Repository
public class BoostRepository {

    private static final String COLUMNS = "id, account_id, starts_at, ends_at, impressions, source";
    private static final String ACTIVE = "starts_at <= boost_owner_now(account_id) AND ends_at > boost_owner_now(account_id)";

    private final JdbcClient jdbc;

    public BoostRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Boost boost, String idempotencyKey) {
        jdbc.sql("""
                        INSERT INTO profile_boosts (id, account_id, starts_at, ends_at, impressions, source, idempotency_key)
                        VALUES (:id, :accountId, :startsAt, :endsAt, :impressions, :source, :key)
                        """)
                .param("id", boost.id())
                .param("accountId", boost.accountId())
                .param("startsAt", utc(boost.startsAt()))
                .param("endsAt", utc(boost.endsAt()))
                .param("impressions", boost.impressions())
                .param("source", boost.source().name())
                .param("key", idempotencyKey)
                .update();
    }

    public Optional<Boost> findByKey(UUID accountId, String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM profile_boosts WHERE account_id = :a AND idempotency_key = :k")
                .param("a", accountId)
                .param("k", idempotencyKey)
                .query(BoostRepository::boost)
                .optional();
    }

    public Optional<Boost> findActive(UUID accountId, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM profile_boosts
                        WHERE account_id = :a AND starts_at <= :now AND ends_at > :now
                        ORDER BY ends_at DESC LIMIT 1
                        """)
                .param("a", accountId)
                .param("now", utc(now))
                .query(BoostRepository::boost)
                .optional();
    }

    /** Newest first. */
    public List<Boost> recent(UUID accountId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM profile_boosts WHERE account_id = :a ORDER BY starts_at DESC, created_at DESC LIMIT :limit")
                .param("a", accountId)
                .param("limit", limit)
                .query(BoostRepository::boost)
                .list();
    }

    /**
     * Ends every boost of the account that has not finished by {@code now} (active or, after the test clock was
     * moved back, not started yet).
     */
    public int endUnfinished(UUID accountId, Instant now) {
        return jdbc.sql("UPDATE profile_boosts SET ends_at = GREATEST(starts_at, :now) WHERE account_id = :a AND ends_at > :now")
                .param("a", accountId)
                .param("now", utc(now))
                .update();
    }

    public void setImpressions(UUID boostId, long impressions) {
        jdbc.sql("UPDATE profile_boosts SET impressions = :n WHERE id = :id")
                .param("id", boostId)
                .param("n", impressions)
                .update();
    }

    /** +1 impression for the active boosts of the given owners, except the viewer's own. */
    public int recordImpressions(UUID viewerAccountId, Collection<UUID> ownerAccountIds) {
        return jdbc.sql("UPDATE profile_boosts SET impressions = impressions + 1"
                        + " WHERE account_id IN (:owners) AND account_id <> :viewer AND " + ACTIVE)
                .param("owners", ownerAccountIds)
                .param("viewer", viewerAccountId)
                .update();
    }

    public Set<UUID> activeAmong(Collection<UUID> accountIds) {
        return new HashSet<>(jdbc.sql("SELECT DISTINCT account_id FROM profile_boosts WHERE account_id IN (:ids) AND " + ACTIVE)
                .param("ids", accountIds)
                .query(UUID.class)
                .list());
    }

    public void deleteAll(UUID accountId) {
        jdbc.sql("DELETE FROM profile_boosts WHERE account_id = :a").param("a", accountId).update();
    }

    private static Boost boost(ResultSet rs, int rowNum) throws SQLException {
        return new Boost(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, OffsetDateTime.class).toInstant(),
                rs.getObject(4, OffsetDateTime.class).toInstant(),
                rs.getLong(5),
                BoostSource.valueOf(rs.getString(6)));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
