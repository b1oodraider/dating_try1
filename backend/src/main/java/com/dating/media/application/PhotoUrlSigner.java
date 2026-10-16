package com.dating.media.application;

import com.dating.common.util.Hashing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Short-lived HMAC-signed photo URLs. Browsers cannot attach a Bearer header to {@code <img src>},
 * so possession of a fresh signed URL is the authorization: URLs are only handed out to the owner
 * and to authenticated users who see the profile in discovery.
 * <p>
 * Expiry is rounded up to a TTL bucket so the same URL is returned for a while and can be cached.
 */
@Component
public class PhotoUrlSigner {

    static final String PATH_PREFIX = "/api/v1/media/photos/";

    private final String secret;
    private final long ttlSeconds;
    private final Clock clock;

    @Autowired
    public PhotoUrlSigner(MediaProperties properties) {
        this(properties.urlSigningSecret(), properties.urlTtl().toSeconds(), Clock.systemUTC());
    }

    PhotoUrlSigner(String secret, long ttlSeconds, Clock clock) {
        this.secret = secret;
        this.ttlSeconds = Math.max(60, ttlSeconds);
        this.clock = clock;
    }

    public String signedUrl(UUID photoId) {
        long now = clock.instant().getEpochSecond();
        long expiresAt = (now / ttlSeconds + 2) * ttlSeconds; // valid between 1 and 2 TTLs
        return PATH_PREFIX + photoId + "?exp=" + expiresAt + "&sig=" + sign(photoId, expiresAt);
    }

    public boolean isValid(UUID photoId, long expiresAt, String signature) {
        long now = clock.instant().getEpochSecond();
        if (expiresAt < now || expiresAt > now + 2 * ttlSeconds + 60) {
            return false;
        }
        return Hashing.constantTimeEquals(sign(photoId, expiresAt), signature);
    }

    public long secondsUntil(long expiresAt) {
        return Math.max(0, expiresAt - clock.instant().getEpochSecond());
    }

    private String sign(UUID photoId, long expiresAt) {
        return Hashing.hmacSha256Hex(secret, "photo:" + photoId + ":" + expiresAt);
    }
}
