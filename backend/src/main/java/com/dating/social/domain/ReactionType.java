package com.dating.social.domain;

/**
 * One reaction per (actor, target); the latest one wins. LIKE and SUPERLIKE count as "liked".
 */
public enum ReactionType {
    LIKE,
    SUPERLIKE,
    PASS;

    public boolean isLike() {
        return this != PASS;
    }
}
