package com.dating.economy.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Admin overrides of the premium composition (FEATURES, ALLOWANCES) as JSON. No row = config default.
 */
@Repository
public class PremiumSettingsRepository {

    public static final String FEATURES = "FEATURES";
    public static final String ALLOWANCES = "ALLOWANCES";

    private final JdbcClient jdbc;

    public PremiumSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> find(String key) {
        return jdbc.sql("SELECT value::text FROM premium_settings WHERE setting_key = :key")
                .param("key", key)
                .query(String.class)
                .optional();
    }

    public void upsert(String key, String json, UUID updatedBy) {
        jdbc.sql("""
                        INSERT INTO premium_settings (setting_key, value, updated_by) VALUES (:key, CAST(:value AS jsonb), :by)
                        ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by,
                                                                updated_at = now()
                        """)
                .param("key", key)
                .param("value", json)
                .param("by", updatedBy)
                .update();
    }

    public void delete(String key) {
        jdbc.sql("DELETE FROM premium_settings WHERE setting_key = :key").param("key", key).update();
    }
}
