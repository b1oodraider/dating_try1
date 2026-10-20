package com.dating.profiles.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The city dictionary (migration 023): one id per city, whatever name people use for it. */
@Repository
public class CityRepository {

    /** @param matchedName the name the query matched when it is not the official one («Питер»), else null */
    public record City(int id, String name, String region, double latitude, double longitude, String matchedName) {
    }

    private final JdbcClient jdbc;

    public CityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Lower case, ё → е, anything but letters and digits → one space; the same rule built city_names.norm. */
    public static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^0-9a-zа-я]+", " ").trim();
    }

    /**
     * Suggestions: exact name first, then names starting with the query, then similar ones (typos); bigger cities
     * first within each group. One row per city.
     */
    public List<City> search(String query, int limit) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, name, region, latitude, longitude, matched FROM (
                          SELECT DISTINCT ON (c.id) c.id, c.name, c.region, c.latitude, c.longitude, c.population,
                                 CASE WHEN n.kind = 'OFFICIAL' THEN NULL ELSE n.name END AS matched,
                                 CASE WHEN n.norm = :q THEN 3 WHEN n.norm LIKE :prefix THEN 2 ELSE 1 END AS rank
                            FROM city_names n JOIN cities c ON c.id = n.city_id
                           WHERE n.norm LIKE :prefix OR n.norm % :q
                           ORDER BY c.id, rank DESC, (n.kind = 'OFFICIAL') DESC
                        ) best
                        ORDER BY rank DESC, population DESC
                        LIMIT :limit
                        """)
                .param("q", q)
                .param("prefix", q.replace("%", "").replace("_", "") + "%")
                .param("limit", limit)
                .query((rs, n) -> new City(rs.getInt("id"), rs.getString("name"), rs.getString("region"),
                        rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("matched")))
                .list();
    }

    public Optional<City> findById(int id) {
        return jdbc.sql("SELECT id, name, region, latitude, longitude FROM cities WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new City(rs.getInt("id"), rs.getString("name"), rs.getString("region"),
                        rs.getDouble("latitude"), rs.getDouble("longitude"), null))
                .optional();
    }

    /** A free-text city of an older client: the biggest city with exactly this name (any kind of name). */
    public Optional<City> resolve(String text) {
        String q = normalize(text);
        if (q.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT c.id, c.name, c.region, c.latitude, c.longitude
                          FROM city_names n JOIN cities c ON c.id = n.city_id
                         WHERE n.norm = :q
                         ORDER BY c.population DESC
                         LIMIT 1
                        """)
                .param("q", q)
                .query((rs, n) -> new City(rs.getInt("id"), rs.getString("name"), rs.getString("region"),
                        rs.getDouble("latitude"), rs.getDouble("longitude"), null))
                .optional();
    }
}
