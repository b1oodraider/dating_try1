package com.dating.media.application;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprints of a stored photo, to find the same picture on several accounts (a fake-profile signal):
 * an exact hash, and a 64-bit difference hash (dHash) that survives re-encoding and resizing.
 *
 * @param dhash null when the JDK cannot decode the format (WebP)
 */
public record PhotoFingerprint(String sha256, Long dhash) {

    public static PhotoFingerprint of(byte[] content) {
        try {
            return new PhotoFingerprint(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                    dhash(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /** Gray 9×8 thumbnail; bit = "this pixel is brighter than its right neighbour". */
    static Long dhash(byte[] content) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (image == null) {
            return null;
        }
        BufferedImage small = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        small.getGraphics().drawImage(image.getScaledInstance(9, 8, Image.SCALE_SMOOTH), 0, 0, null);
        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = small.getRaster().getSample(x, y, 0);
                int right = small.getRaster().getSample(x + 1, y, 0);
                hash = (hash << 1) | (left > right ? 1 : 0);
            }
        }
        return hash;
    }
}
