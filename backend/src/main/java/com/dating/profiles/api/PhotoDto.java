package com.dating.profiles.api;

import java.time.Instant;
import java.util.UUID;

/**
 * @param url         relative signed URL, usable without an Authorization header (e.g. in &lt;img src&gt;)
 * @param underReview the face did not match my verified photos: only I (and moderators) see it until it is checked
 */
public record PhotoDto(
        UUID id,
        String url,
        String mimeType,
        long sizeBytes,
        int sortOrder,
        boolean isPrimary,
        Instant createdAt,
        boolean underReview
) {}
