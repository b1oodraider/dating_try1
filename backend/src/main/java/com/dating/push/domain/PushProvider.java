package com.dating.push.domain;

/**
 * Where a device token comes from. Stored as text; keep in sync with migration 014.
 */
public enum PushProvider {
    /** RuStore Push (Android devices in Russia without Google services). */
    RUSTORE,
    /** Firebase Cloud Messaging (Android with Google services, iOS through APNs). */
    FCM
}
