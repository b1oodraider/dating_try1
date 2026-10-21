package com.dating.push.persistence;

import com.dating.push.domain.PushProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class PushDeviceRepository {

    public record Device(String token, PushProvider provider) {
    }

    private final JdbcClient jdbc;

    public PushDeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One device per session: a refreshed token replaces the old one; a token seen under another session
     * (re-login on the same phone) moves to the new one.
     */
    public void register(UUID accountId, UUID sessionId, PushProvider provider, String token) {
        jdbc.sql("DELETE FROM push_devices WHERE session_id = :session AND token <> :token")
                .param("session", sessionId)
                .param("token", token)
                .update();
        jdbc.sql("""
                        INSERT INTO push_devices (token, provider, account_id, session_id)
                        VALUES (:token, :provider, :account, :session)
                        ON CONFLICT (token) DO UPDATE
                          SET provider = EXCLUDED.provider, account_id = EXCLUDED.account_id,
                              session_id = EXCLUDED.session_id, updated_at = now()
                        """)
                .param("token", token)
                .param("provider", provider.name())
                .param("account", accountId)
                .param("session", sessionId)
                .update();
    }

    public void unregister(UUID sessionId) {
        jdbc.sql("DELETE FROM push_devices WHERE session_id = :session").param("session", sessionId).update();
    }

    /** Devices whose session can still be used (not revoked, not expired). */
    public List<Device> activeDevices(UUID accountId) {
        return jdbc.sql("""
                        SELECT d.token, d.provider
                          FROM push_devices d
                          JOIN sessions s ON s.id = d.session_id
                         WHERE d.account_id = :account AND s.revoked_at IS NULL AND s.expires_at > now()
                        """)
                .param("account", accountId)
                .query((rs, n) -> new Device(rs.getString(1), PushProvider.valueOf(rs.getString(2))))
                .list();
    }

    /**
     * Accounts with a published profile and a push device whose last active day was exactly {@code daysAgo}
     * days ago (one reminder per threshold; test accounts are skipped).
     */
    public List<UUID> inactiveFor(int daysAgo) {
        return jdbc.sql("""
                        SELECT a.id FROM accounts a
                          JOIN profiles p ON p.account_id = a.id AND p.publication_status = 'PUBLISHED'
                         WHERE a.status = 'ACTIVE' AND NOT a.test_account
                           AND EXISTS (SELECT 1 FROM push_devices d WHERE d.account_id = a.id)
                           AND (SELECT max(day) FROM account_activity_days ad WHERE ad.account_id = a.id)
                               = current_date - :days
                        """)
                .param("days", daysAgo)
                .query(UUID.class)
                .list();
    }

    public void delete(String token) {
        jdbc.sql("DELETE FROM push_devices WHERE token = :token").param("token", token).update();
    }
}
