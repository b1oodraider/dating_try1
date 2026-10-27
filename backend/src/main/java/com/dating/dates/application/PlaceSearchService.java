package com.dating.dates.application;

import com.dating.common.errors.ApiException;
import com.dating.common.ratelimit.FixedWindowRateLimiter;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Search by address or place name for the date plan map (docs/date-plan.md), near the map centre.
 * shortcut: the public Photon server over OpenStreetMap (no key, fair use only, «© OpenStreetMap» on the map) — for
 * testing; before release switch to the 2GIS search API together with the 2GIS tiles (one agreement).
 */
@Service
public class PlaceSearchService {

    /** @param enabled off in tests and when the server has no internet */
    @ConfigurationProperties(prefix = "app.places")
    public record PlacesProperties(@DefaultValue("true") boolean enabled,
                                   @DefaultValue("https://photon.komoot.io/api/") String photonUrl) {
    }

    /** A found place: what the plan item needs. */
    public record Place(String name, String address, double latitude, double longitude) {
    }

    private static final Logger log = LoggerFactory.getLogger(PlaceSearchService.class);
    private static final int LIMIT = 8;
    private static final String USER_AGENT = "InstarejectDating/0.1 ( https://instareject.ru )";

    private final PlacesProperties properties;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    // Typing sends a request per pause: 30 a minute per person is plenty and keeps us within fair use.
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(30, Duration.ofMinutes(1));

    public PlaceSearchService(PlacesProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public List<Place> search(UUID me, String query, Double latitude, Double longitude) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2 || !properties.enabled()) {
            return List.of();
        }
        if (q.length() > 120) {
            q = q.substring(0, 120);
        }
        long wait = limiter.tryAcquire(me.toString());
        if (wait > 0) {
            throw ApiException.tooManyRequests("Слишком много запросов. Подождите немного", wait);
        }
        StringBuilder url = new StringBuilder(properties.photonUrl())
                .append("?limit=").append(LIMIT)
                .append("&q=").append(URLEncoder.encode(q, StandardCharsets.UTF_8));
        if (latitude != null && longitude != null) {
            url.append(String.format(Locale.ROOT, "&lat=%.5f&lon=%.5f", latitude, longitude));
        }
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url.toString()))
                            .timeout(Duration.ofSeconds(5))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Place search answered {}", response.statusCode());
                return List.of();
            }
            return parse(json.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Picking a point on the map still works; a failed search is not worth an error to the user.
            log.warn("Place search failed: {}", e.toString());
        }
        return List.of();
    }

    /** Photon GeoJSON → places; a place without a name is named by its street address. */
    static List<Place> parse(JsonNode root) {
        List<Place> places = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode feature : root.path("features")) {
            JsonNode coordinates = feature.path("geometry").path("coordinates");
            if (coordinates.size() < 2) {
                continue;
            }
            JsonNode p = feature.path("properties");
            String street = join(" ", text(p, "street"), text(p, "housenumber"));
            String name = text(p, "name");
            if (name == null) {
                name = street;
            }
            if (name == null) {
                continue;
            }
            String address = join(", ", name.equals(street) ? null : street,
                    text(p, "district"), text(p, "city"));
            Place place = new Place(name, address, coordinates.get(1).asDouble(), coordinates.get(0).asDouble());
            if (seen.add(place.name() + "|" + place.address())) {
                places.add(place);
            }
        }
        return places;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asString("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String join(String separator, String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null) {
                present.add(part);
            }
        }
        return present.isEmpty() ? null : String.join(separator, present);
    }
}
