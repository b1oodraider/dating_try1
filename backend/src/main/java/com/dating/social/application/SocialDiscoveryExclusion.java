package com.dating.social.application;

import com.dating.discovery.application.DiscoveryExclusion;
import com.dating.profiles.domain.Profile;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Feed filter contributed by the social module: hides profiles the viewer already liked / super liked,
 * passed less than app.social.pass-ttl ago, and blocks in either direction.
 * Runs in SQL (function social_hides_profile, migration 012) before pagination, so totals stay exact.
 */
@Component
public class SocialDiscoveryExclusion implements DiscoveryExclusion {

    private final SocialProperties properties;

    public SocialDiscoveryExclusion(SocialProperties properties) {
        this.properties = properties;
    }

    @Override
    public Specification<Profile> visibleTo(UUID viewerAccountId) {
        long passTtlSeconds = properties.passTtl().toSeconds();
        return (root, q, cb) -> cb.isFalse(cb.function("social_hides_profile", Boolean.class,
                cb.literal(viewerAccountId), root.get("accountId"), cb.literal(passTtlSeconds)));
    }
}
