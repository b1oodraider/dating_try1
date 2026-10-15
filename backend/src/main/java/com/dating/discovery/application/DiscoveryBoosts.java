package com.dating.discovery.application;

import com.dating.profiles.domain.Profile;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Boosted profiles in the feed. Implemented by the boost module; declared here so discovery does not depend on it.
 * <p>
 * A boost never widens the feed: it only reorders profiles that have already passed every filter
 * (city, age, tags, premium filters, exclusion words and goals, test/real isolation).
 */
public interface DiscoveryBoosts {

    /**
     * Sort keys placed before the default feed order: boosted profiles first, in an order that is
     * stable for one viewer (consistent pagination) and differs between viewers (fair rotation).
     */
    List<Order> leadingOrders(Root<Profile> root, CriteriaBuilder cb, UUID viewerAccountId);

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
