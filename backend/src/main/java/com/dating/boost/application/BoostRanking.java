package com.dating.boost.application;

import com.dating.boost.persistence.BoostRepository;
import com.dating.discovery.application.DiscoveryBoosts;
import com.dating.profiles.domain.Profile;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Boosts in discovery, done in SQL so pagination and totals stay exact:
 * ORDER BY active_boost_order_key(owner, viewer) ASC NULLS LAST, then the default feed order.
 * The key (migration 011) is NULL without an active boost, otherwise md5(boost id, viewer): a fixed order
 * for one viewer while paging, an independent shuffle for each viewer, so boosted profiles share the top fairly.
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
    public List<Order> leadingOrders(Root<Profile> root, CriteriaBuilder cb, UUID viewerAccountId) {
        return List.of(cb.asc(cb.function("active_boost_order_key", String.class,
                root.get("accountId"), cb.literal(viewerAccountId.toString())), Nulls.LAST));
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
