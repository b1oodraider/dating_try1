package com.dating.discovery.application;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Boosted profiles in the feed. Implemented by the boost module; declared here so discovery does not depend on it.
 * <p>
 * A boost never widens the feed: it adds points to the score of profiles that already passed every filter
 * (SQL feed_score, migration 024).
 */
public interface DiscoveryBoosts {

    /**
     * @return owners among the given accounts whose boost may be shown to others right now
     * (always empty unless app.boost.public-badge=true)
     */
    Set<UUID> publicBadgesAmong(Collection<UUID> ownerAccountIds);

    /**
     * Counts one impression for every active boost among the owners of the profiles served to the viewer
     * on one page. One batched statement per page; the viewer's own boost is never counted.
     */
    void recordImpressions(UUID viewerAccountId, Collection<UUID> ownerAccountIds);
}
