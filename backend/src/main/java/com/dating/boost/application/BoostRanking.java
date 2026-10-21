package com.dating.boost.application;

import com.dating.boost.persistence.BoostRepository;
import com.dating.discovery.application.DiscoveryBoosts;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Boosts in discovery: the points a boost adds live in the SQL feed score (migration 024); this side shows the
 * badge and counts impressions.
 */
@Component
public class BoostRanking implements DiscoveryBoosts {

    private final BoostRepository repository;
    private final BoostProperties properties;

    public BoostRanking(BoostRepository repository, BoostProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public Set<UUID> publicBadgesAmong(Collection<UUID> ownerAccountIds) {
        if (!properties.publicBadge() || ownerAccountIds.isEmpty()) {
            return Set.of();
        }
        return repository.activeAmong(ownerAccountIds);
    }

    @Override
    public void recordImpressions(UUID viewerAccountId, Collection<UUID> ownerAccountIds) {
        if (!ownerAccountIds.isEmpty()) {
            repository.recordImpressions(viewerAccountId, ownerAccountIds);
        }
    }
}
