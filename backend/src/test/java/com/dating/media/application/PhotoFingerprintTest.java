package com.dating.media.application;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoFingerprintTest {

    private static BufferedImage picture(int width, int height, boolean mirrored) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int x = 0; x < width; x++) {
            int shade = (int) (255.0 * (mirrored ? width - 1 - x : x) / (width - 1));
            g.setColor(new Color(shade, (shade * 3) % 256, 255 - shade));
            g.fillRect(x, 0, 1, height / 2);
            g.setColor(new Color(255 - shade, shade, (shade * 7) % 256));
            g.fillRect(x, height / 2, 1, height - height / 2);
        }
        g.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    @Test
    void reEncodedAndResizedCopiesKeepNearlyTheSameDhash() throws IOException {
        Long png = PhotoFingerprint.of(encode(picture(320, 240, false), "png")).dhash();
        Long smallJpeg = PhotoFingerprint.of(encode(picture(160, 120, false), "jpg")).dhash();
        Long other = PhotoFingerprint.of(encode(picture(320, 240, true), "png")).dhash();
        assertNotNull(png);
        assertTrue(Long.bitCount(png ^ smallJpeg) <= 6, "copy: " + Long.bitCount(png ^ smallJpeg) + " bits differ");
        assertTrue(Long.bitCount(png ^ other) > 6, "different picture: " + Long.bitCount(png ^ other) + " bits differ");
    }

    @Test
    void exactHashDiffersForDifferentBytesAndUnknownFormatsHaveNoDhash() throws IOException {
        assertNotEquals(PhotoFingerprint.of(encode(picture(32, 32, false), "png")).sha256(),
                PhotoFingerprint.of(encode(picture(32, 32, true), "png")).sha256());
        assertNull(PhotoFingerprint.of(new byte[]{1, 2, 3}).dhash());
    }
}
