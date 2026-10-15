package com.dating.social.domain;

/**
 * Why a profile (or one of its messages) was reported. Stored as text; keep in sync with migration 012.
 */
public enum ReportReason {
    SPAM,
    FAKE_PROFILE,
    INAPPROPRIATE_CONTENT,
    HARASSMENT,
    SCAM,
    UNDERAGE,
    OTHER
}
