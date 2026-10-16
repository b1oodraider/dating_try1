package com.dating.discovery.application;

import com.dating.profiles.domain.Profile;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/**
 * A hard filter for the feed contributed by another module (e.g. social: already liked / passed, blocked),
 * so discovery does not depend on those modules. Applied in SQL before pagination.
 */
public interface DiscoveryExclusion {

    /**
     * @return a predicate that is true for profiles the viewer may still see
     */
    Specification<Profile> visibleTo(UUID viewerAccountId);
}
