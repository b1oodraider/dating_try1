package com.dating.gacha.domain;

/**
 * Why a pull was restricted to a subset of the pool (stored in the history for audit).
 * A pull without a trigger is an ordinary weighted roll over the whole pool.
 */
public enum PityTrigger {
    /** The "EPIC or better within N pulls" guarantee fired. */
    EPIC_PITY,
    /** The "LEGENDARY at M pulls" guarantee fired. */
    LEGENDARY_PITY,
    /** Testing API: the result was taken from the account's forced queue. */
    FORCED
}
