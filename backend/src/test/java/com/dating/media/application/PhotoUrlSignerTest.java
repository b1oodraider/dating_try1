package com.dating.media.application;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PhotoUrlSignerTest {

    private static final String SECRET = "test-secret-test-secret-test-secret";
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final PhotoUrlSigner signer = new PhotoUrlSigner(SECRET, 3600, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void signedUrlVerifies() {
        UUID photoId = UUID.randomUUID();
        UriComponents url = UriComponentsBuilder.fromUriString(signer.signedUrl(photoId)).build();

        assertEquals("/api/v1/media/photos/" + photoId, url.getPath());
        long exp = Long.parseLong(url.getQueryParams().getFirst("exp"));
        String sig = url.getQueryParams().getFirst("sig");

        assertTrue(exp > NOW.getEpochSecond());
        assertTrue(signer.isValid(photoId, exp, sig));
    }

    @Test
    void urlIsStableWithinTtlBucketForCaching() {
        UUID photoId = UUID.randomUUID();
        PhotoUrlSigner later = new PhotoUrlSigner(SECRET, 3600, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
        assertEquals(signer.signedUrl(photoId), later.signedUrl(photoId));
    }

    @Test
    void rejectsTamperingOtherPhotoAndExpiry() {
        UUID photoId = UUID.randomUUID();
        UriComponents url = UriComponentsBuilder.fromUriString(signer.signedUrl(photoId)).build();
        long exp = Long.parseLong(url.getQueryParams().getFirst("exp"));
        String sig = url.getQueryParams().getFirst("sig");

        assertFalse(signer.isValid(photoId, exp, sig.substring(1) + "0"), "tampered signature");
        assertFalse(signer.isValid(UUID.randomUUID(), exp, sig), "signature of another photo");
        assertFalse(signer.isValid(photoId, exp + 1, sig), "changed expiry");
        assertFalse(signer.isValid(photoId, exp, null), "missing signature");

        PhotoUrlSigner afterExpiry = new PhotoUrlSigner(SECRET, 3600, Clock.fixed(Instant.ofEpochSecond(exp + 1), ZoneOffset.UTC));
        assertFalse(afterExpiry.isValid(photoId, exp, sig), "expired");

        PhotoUrlSigner otherSecret = new PhotoUrlSigner(SECRET + "x", 3600, Clock.fixed(NOW, ZoneOffset.UTC));
        assertFalse(otherSecret.isValid(photoId, exp, sig), "other secret");
    }
}
