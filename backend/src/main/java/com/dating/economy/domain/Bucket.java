package com.dating.economy.domain;

/**
 * Where a consumable unit came from. Keeping them apart lets premium allowances refill
 * without "eating" bought/earned units, and lets periodic units be spent first.
 */
public enum Bucket {
    /** Bought, earned (achievements), won (gacha), granted by support. Never expires. */
    PERMANENT,
    /** Premium allowance. Refilled every period; spent before PERMANENT. */
    PERIODIC
}
