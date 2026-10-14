package com.dating.common.entitlements;

import java.util.UUID;

/**
 * Checks whether an account may use a feature. Features are identified by string codes so that
 * the set of premium features can change via configuration, without code changes in other modules.
 * <p>
 * Usage in a service: {@code featureAccess.require(accountId, "ADVANCED_FILTERS");}
 */
public interface FeatureAccess {

    /**
     * @return true if the feature is not premium-only, or the account currently has premium
     */
    boolean isAllowed(UUID accountId, String feature);

    /**
     * @throws com.dating.common.errors.ApiException 403 PREMIUM_REQUIRED (details.feature) if not allowed
     */
    void require(UUID accountId, String feature);
}
