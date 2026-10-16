package com.dating.profiles.application;

import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.persistence.ProfileRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** "How much we match by cards" for the viewer, next to every profile they see (see {@link Compatibility}). */
@Service
public class CompatibilityService {

    private final ProfileRepository profileRepository;
    private final JdbcClient jdbc;

    public CompatibilityService(ProfileRepository profileRepository, JdbcClient jdbc) {
        this.profileRepository = profileRepository;
        this.jdbc = jdbc;
    }

    /** @return percent by account id of the shown profile; absent when there is too little to compare */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> forViewer(UUID viewerAccountId, Collection<Profile> shown) {
        if (viewerAccountId == null || shown.isEmpty()) {
            return Map.of();
        }
        List<Compatibility.Card> mine = profileRepository.findByAccountId(viewerAccountId)
                .map(CompatibilityService::cards).orElse(List.of());
        if (mine.isEmpty()) {
            return Map.of();
        }
        Map<String, Compatibility.Scale> scales = scales();
        Map<UUID, Integer> result = new HashMap<>();
        for (Profile profile : shown) {
            Integer percent = Compatibility.percent(mine, cards(profile), scales);
            if (percent != null) {
                result.put(profile.getAccountId(), percent);
            }
        }
        return result;
    }

    /** Scale categories with the range of positions their active cards use. */
    Map<String, Compatibility.Scale> scales() {
        return jdbc.sql("""
                        SELECT t.category, min(t.scale_position), max(t.scale_position)
                          FROM communication_tags t JOIN tag_categories c ON c.code = t.category AND c.scale
                         WHERE t.active AND t.scale_position IS NOT NULL
                         GROUP BY t.category
                        """)
                .query((rs, n) -> Map.entry(rs.getString(1), new Compatibility.Scale(rs.getInt(2), rs.getInt(3))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static List<Compatibility.Card> cards(Profile profile) {
        return profile.getTags().stream()
                .filter(CommunicationTag::isActive)
                .map(t -> new Compatibility.Card(t.getCode(), t.getCategory(), t.getScalePosition()))
                .toList();
    }
}
