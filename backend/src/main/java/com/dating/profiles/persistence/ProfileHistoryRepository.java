package com.dating.profiles.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Previous states of profiles (the data a person changed or replaced with «fill in again»). */
@Repository
public class ProfileHistoryRepository {

    private final JdbcClient jdbc;

    public ProfileHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(UUID profileId, String snapshotJson) {
        jdbc.sql("INSERT INTO profile_history (profile_id, snapshot) VALUES (:p, CAST(:s AS jsonb))")
                .param("p", profileId).param("s", snapshotJson).update();
    }

    public int count(UUID profileId) {
        return jdbc.sql("SELECT count(*) FROM profile_history WHERE profile_id = :p")
                .param("p", profileId).query(Integer.class).single();
    }
}
