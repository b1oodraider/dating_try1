package com.dating.social.domain;

/**
 * Why a profile (or one of its messages) was reported. Stored as text; keep in sync with migrations 012 and 021.
 */
public enum ReportReason {
    SPAM,
    FAKE_PROFILE,
    INAPPROPRIATE_CONTENT,
    HARASSMENT,
    SCAM,
    UNDERAGE,
    OTHER,
    /** Silence is not a violation: closed by the system at once, never reaches a moderator. */
    NOT_RESPONDING
}
