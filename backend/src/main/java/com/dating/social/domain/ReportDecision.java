package com.dating.social.domain;

/**
 * Moderator's verdict on a report. Stored as text; keep in sync with migration 013.
 */
public enum ReportDecision {
    /** The violation is confirmed as reported. */
    ACCEPTED,
    /** A violation is confirmed, but of a different kind than reported (final reason differs). */
    RECLASSIFIED,
    /** No violation. */
    REJECTED
}
