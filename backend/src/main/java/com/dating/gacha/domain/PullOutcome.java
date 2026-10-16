package com.dating.gacha.domain;

/**
 * How a rolled entry was credited.
 */
public enum PullOutcome {
    /** Granted as is: a cosmetic the account did not own, a consumable or premium days. */
    NEW,
    /** An owned cosmetic (or one rolled twice in the same multi-pull) converted into shards. */
    DUPLICATE_SHARDS
}
