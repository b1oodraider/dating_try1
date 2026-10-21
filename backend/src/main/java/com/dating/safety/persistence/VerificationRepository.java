package com.dating.safety.persistence;

import com.dating.safety.domain.Gesture;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VerificationRepository {

    public record Request(UUID id, UUID accountId, Gesture gesture, String status, String selfieKey, String selfieMime,
                          Instant createdAt, Instant expiresAt, Instant submittedAt, Instant decidedAt, UUID decidedBy,
                          String rejectReason) {
    }

    private static final String COLUMNS = """
            id, account_id, gesture, status, selfie_key, selfie_mime, created_at, expires_at, submitted_at,
            decided_at, decided_by, reject_reason
            """;

    private final JdbcClient jdbc;

    public VerificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockAccount(UUID accountId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('verification:' || :account, 0))")
                .param("account", accountId.toString()).query((rs, n) -> 1).list();
    }

    public void expireStale(UUID accountId) {
        jdbc.sql("""
                        UPDATE verification_requests SET status = 'EXPIRED'
                         WHERE account_id = :account AND status = 'AWAITING_SELFIE' AND expires_at <= now()
                        """)
                .param("account", accountId).update();
    }

    /** The caller has the user's consent to comparing their photos (stored as face_consent_at). */
    public Request create(UUID accountId, Gesture gesture, int minutesToUpload) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO verification_requests (id, account_id, gesture, status, expires_at, face_consent_at)
                        VALUES (:id, :account, :gesture, 'AWAITING_SELFIE', now() + make_interval(mins => :minutes), now())
                        """)
                .param("id", id).param("account", accountId).param("gesture", gesture.name())
                .param("minutes", minutesToUpload).update();
        return find(id).orElseThrow();
    }

    public Optional<Request> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM verification_requests WHERE id = :id")
                .param("id", id).query(VerificationRepository::map).optional();
    }

    public Optional<Request> findForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM verification_requests WHERE id = :id FOR UPDATE")
                .param("id", id).query(VerificationRepository::map).optional();
    }

    public Optional<Request> latest(UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM verification_requests WHERE account_id = :account"
                        + " ORDER BY created_at DESC LIMIT 1")
                .param("account", accountId).query(VerificationRepository::map).optional();
    }

    public int countSince(UUID accountId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM verification_requests WHERE account_id = :account AND created_at > :since")
                .param("account", accountId).param("since", OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC))
                .query(Integer.class).single();
    }

    public void submit(UUID id, String selfieKey, String mime) {
        jdbc.sql("""
                        UPDATE verification_requests SET status = 'PENDING', selfie_key = :key, selfie_mime = :mime,
                               submitted_at = now()
                         WHERE id = :id
                        """)
                .param("id", id).param("key", selfieKey).param("mime", mime).update();
    }

    /** The decision also forgets the selfie: the file is deleted after commit. */
    public void decide(UUID id, String status, UUID moderator, String rejectReason) {
        jdbc.sql("""
                        UPDATE verification_requests SET status = :status, decided_at = now(), decided_by = :moderator,
                               reject_reason = :reason, selfie_key = NULL, selfie_mime = NULL
                         WHERE id = :id
                        """)
                .param("id", id).param("status", status).param("moderator", moderator).param("reason", rejectReason)
                .update();
    }

    /** Oldest first. */
    public List<Request> page(Collection<String> statuses, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM verification_requests WHERE status IN (:statuses)
                        ORDER BY coalesce(submitted_at, created_at), id LIMIT :limit OFFSET :offset
                        """)
                .param("statuses", statuses).param("limit", limit).param("offset", offset)
                .query(VerificationRepository::map).list();
    }

    public long count(Collection<String> statuses) {
        return jdbc.sql("SELECT count(*) FROM verification_requests WHERE status IN (:statuses)")
                .param("statuses", statuses).query(Long.class).single();
    }

    public void setVerified(UUID accountId, boolean verified) {
        jdbc.sql("UPDATE profiles SET verified_at = " + (verified ? "now()" : "NULL") + " WHERE account_id = :account")
                .param("account", accountId).update();
    }

    public Optional<Instant> verifiedAt(UUID accountId) {
        return jdbc.sql("SELECT verified_at FROM profiles WHERE account_id = :account AND verified_at IS NOT NULL")
                .param("account", accountId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant()).optional();
    }

    /** @return selfie files still stored (to delete them) */
    public List<String> deleteAccountData(UUID accountId) {
        List<String> keys = jdbc.sql("SELECT selfie_key FROM verification_requests WHERE account_id = :account"
                        + " AND selfie_key IS NOT NULL")
                .param("account", accountId).query(String.class).list();
        jdbc.sql("DELETE FROM verification_requests WHERE account_id = :account").param("account", accountId).update();
        return keys;
    }

    private static Request map(ResultSet rs, int n) throws SQLException {
        return new Request(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                Gesture.valueOf(rs.getString("gesture")),
                rs.getString("status"),
                rs.getString("selfie_key"),
                rs.getString("selfie_mime"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                instant(rs, "submitted_at"),
                instant(rs, "decided_at"),
                rs.getObject("decided_by", UUID.class),
                rs.getString("reject_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
