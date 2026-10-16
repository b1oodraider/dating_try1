package com.dating.media.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.media")
public record MediaProperties(
        @DefaultValue("./data/media") String storagePath,
        @DefaultValue("5MB") DataSize maxFileSize,
        @DefaultValue("6") int maxPhotosPerProfile,
        String urlSigningSecret,
        @DefaultValue("1h") Duration urlTtl
) {
    private static final int MIN_SECRET_LENGTH = 32;

    public MediaProperties {
        if (urlSigningSecret == null || urlSigningSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "app.media.url-signing-secret (env APP_MEDIA_URL_SIGNING_SECRET) must be set, at least "
                            + MIN_SECRET_LENGTH + " characters");
        }
    }
}
