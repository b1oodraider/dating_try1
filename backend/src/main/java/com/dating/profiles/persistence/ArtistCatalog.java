package com.dating.profiles.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Artist search (prefix first, then similar spelling) and adding artists found on MusicBrainz. */
@Repository
public class ArtistCatalog {

    public record Found(long id, String name) {
    }

    private final JdbcClient jdbc;

    public ArtistCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ").trim();
    }

    public List<Found> search(String query, int limit) {
        String q = normalize(query);
        return jdbc.sql("""
                        SELECT id, name FROM artists
                         WHERE name_normalized LIKE :prefix OR name_normalized LIKE :inside OR similarity(name_normalized, :q) > 0.35
                         ORDER BY (name_normalized LIKE :prefix) DESC, similarity(name_normalized, :q) DESC, name
                         LIMIT :limit
                        """)
                .param("prefix", escape(q) + "%")
                .param("inside", "% " + escape(q) + "%")
                .param("q", q)
                .param("limit", limit)
                .query((rs, n) -> new Found(rs.getLong(1), rs.getString(2)))
                .list();
    }

    /** Idempotent by MusicBrainz id and by normalized name. */
    public void addFromMusicBrainz(String name, UUID mbid) {
        jdbc.sql("""
                        INSERT INTO artists (name, name_normalized, mbid, source) VALUES (:name, :norm, :mbid, 'MUSICBRAINZ')
                        ON CONFLICT DO NOTHING
                        """)
                .param("name", name).param("norm", normalize(name)).param("mbid", mbid).update();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
