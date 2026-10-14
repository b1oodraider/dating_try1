package com.dating.media.application;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Image type detected from the file's magic bytes. The client-supplied Content-Type is never trusted.
 */
public enum ImageFormat {
    JPEG("image/jpeg", ".jpg"),
    PNG("image/png", ".png"),
    WEBP("image/webp", ".webp");

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final String mimeType;
    private final String extension;

    ImageFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<ImageFormat> detect(byte[] data) {
        if (data == null || data.length < 12) {
            return Optional.empty();
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (startsWith(data, PNG_SIGNATURE)) {
            return Optional.of(PNG);
        }
        if ("RIFF".equals(ascii(data, 0)) && "WEBP".equals(ascii(data, 8))) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] data, int offset) {
        return new String(data, offset, 4, StandardCharsets.US_ASCII);
    }
}
