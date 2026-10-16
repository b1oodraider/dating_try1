package com.dating.safety.domain;

/**
 * What an account may not do. Stored as text; keep in sync with migration 015.
 */
public enum RestrictionKind {
    /** No likes or super likes (passing is allowed). */
    LIKES,
    /** No chat messages. */
    MESSAGES,
    /** The profile is out of the feed and out of everybody's "who liked me". */
    HIDDEN,
    /** Login closed, profile hidden; applying it removes all likes of the account and closes its matches. */
    SUSPENDED,
    /** Hidden until a moderator confirms the person is real (lifted by a moderator). */
    VERIFICATION_REQUIRED
}
