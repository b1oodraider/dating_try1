package com.dating.profiles.application;

import com.dating.profiles.api.ArtistDto;
import com.dating.profiles.persistence.ArtistCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Artist search for the «favourite music» field: our dictionary first; when it has few matches, MusicBrainz is
 * asked once per query and its artists join the dictionary (docs/matching.md, part 1).
 */
@Service
public class ArtistSearchService {

    /** @param musicbrainzEnabled off in tests and when the server has no internet */
    @ConfigurationProperties(prefix = "app.music")
    public record MusicProperties(@DefaultValue("true") boolean musicbrainzEnabled) {
    }

    private static final Logger log = LoggerFactory.getLogger(ArtistSearchService.class);
    private static final int LIMIT = 10;
    private static final int MIN_SCORE = 85;
    // MusicBrainz asks for a meaningful User-Agent and at most 1 request per second.
    private static final String USER_AGENT = "InstarejectDating/0.1 ( https://instareject.ru )";

    private final ArtistCatalog catalog;
    private final MusicProperties properties;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    // shortcut: in-memory, per instance; queries already sent to MusicBrainz are not sent again until restart.
    private final Set<String> asked = ConcurrentHashMap.newKeySet();
    private long lastCallAt;

    public ArtistSearchService(ArtistCatalog catalog, MusicProperties properties, JsonMapper json) {
        this.catalog = catalog;
        this.properties = properties;
        this.json = json;
    }

    public List<ArtistDto> search(String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) {
            return List.of();
        }
        if (q.length() > 100) {
            q = q.substring(0, 100);
        }
        List<ArtistCatalog.Found> found = catalog.search(q, LIMIT);
        String key = ArtistCatalog.normalize(q);
        // Similar spellings («James Brown» for «Etta James») do not count: ask when nothing starts with the query.
        boolean weak = found.size() < 5
                || found.stream().noneMatch(f -> ArtistCatalog.normalize(f.name()).startsWith(key));
        if (weak && q.length() >= 3 && properties.musicbrainzEnabled()
                && asked.size() < 50_000 && asked.add(key)) {
            if (!askMusicBrainz(q)) {
                asked.remove(key); // a failed lookup is retried next time
            }
            found = catalog.search(q, LIMIT);
        }
        return found.stream().map(f -> new ArtistDto(f.id(), f.name())).toList();
    }

    /** @return false when MusicBrainz could not be asked (network, rate limit) */
    private boolean askMusicBrainz(String q) {
        try {
            throttle();
            String url = "https://musicbrainz.org/ws/2/artist?fmt=json&limit=8&query="
                    + URLEncoder.encode("artist:\"" + q.replace("\"", "") + "\"", StandardCharsets.UTF_8);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(4))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("MusicBrainz answered {}", response.statusCode());
                return false;
            }
            for (JsonNode artist : json.readTree(response.body()).path("artists")) {
                if (artist.path("score").asInt(0) < MIN_SCORE) {
                    continue;
                }
                String name = artist.path("name").asString("");
                String id = artist.path("id").asString("");
                if (!name.isBlank() && name.length() <= 200 && !id.isBlank()) {
                    catalog.addFromMusicBrainz(name, UUID.fromString(id));
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // The dictionary still answers; a missing artist is not worth an error to the user.
            log.warn("MusicBrainz lookup failed: {}", e.toString());
        }
        return false;
    }

    private synchronized void throttle() throws InterruptedException {
        long wait = lastCallAt + 1100 - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastCallAt = System.currentTimeMillis();
    }
}
