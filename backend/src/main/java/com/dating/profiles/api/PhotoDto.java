package com.dating.profiles.api;

import java.time.Instant;
import java.util.UUID;

/**
 * @param url relative signed URL, usable without an Authorization header (e.g. in &lt;img src&gt;)
 */
public record PhotoDto(
        UUID id,
        String url,
        String mimeType,
        long sizeBytes,
        int sortOrder,
        boolean isPrimary,
        Instant createdAt
) {}
