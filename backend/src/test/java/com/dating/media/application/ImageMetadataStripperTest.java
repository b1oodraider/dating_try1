package com.dating.media.application;

import com.dating.support.TestImages;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ImageMetadataStripperTest {

    private static final String SECRET = "GPS-SECRET-55.7558N-37.6173E";

    @Test
    void jpegExifAndCommentsAreRemovedButOrientationIsKept() throws IOException {
        byte[] input = TestImages.jpegWithExif(TestImages.jpeg(8, 8), 6, SECRET);
        assertTrue(TestImages.contains(input, SECRET), "precondition: secret planted");

        byte[] output = ImageMetadataStripper.strip(input, ImageFormat.JPEG);

        assertFalse(TestImages.contains(output, SECRET), "EXIF payload / comment must be gone");
        assertTrue(TestImages.contains(output, new byte[]{0x01, 0x12, 0x00, 0x03, 0, 0, 0, 1, 0, 6}),
                "orientation 6 must survive in a minimal EXIF block");
        assertNotNull(ImageIO.read(new ByteArrayInputStream(output)), "still a decodable JPEG");
    }

    @Test
    void jpegWithoutMetadataStaysDecodable() throws IOException {
        byte[] output = ImageMetadataStripper.strip(TestImages.jpeg(4, 4), ImageFormat.JPEG);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(output)));
        assertFalse(TestImages.contains(output, "Exif"));
    }

    @Test
    void pngTextChunksAreRemoved() throws IOException {
        byte[] input = TestImages.pngWithText(TestImages.png(4, 4), SECRET);
        byte[] output = ImageMetadataStripper.strip(input, ImageFormat.PNG);

        assertFalse(TestImages.contains(output, SECRET));
        assertFalse(TestImages.contains(output, "tEXt"));
        assertNotNull(ImageIO.read(new ByteArrayInputStream(output)));
    }

    @Test
    void webpExifAndXmpChunksAreRemovedAndHeaderFixed() {
        byte[] input = TestImages.webpWithMetadata(SECRET);
        byte[] output = ImageMetadataStripper.strip(input, ImageFormat.WEBP);

        assertFalse(TestImages.contains(output, SECRET));
        assertFalse(TestImages.contains(output, "EXIF"));
        assertTrue(TestImages.contains(output, "VP8L"));

        int riffSize = (output[4] & 0xFF) | (output[5] & 0xFF) << 8 | (output[6] & 0xFF) << 16 | (output[7] & 0xFF) << 24;
        assertEquals(output.length - 8, riffSize, "RIFF size must match the new length");
        int vp8xFlags = output[12 + 8] & 0xFF;
        assertEquals(0, vp8xFlags & 0x0C, "EXIF/XMP flags must be cleared");
    }

    @Test
    void detectsFormatByContentNotByName() {
        assertEquals(ImageFormat.PNG, ImageFormat.detect(TestImages.png(2, 2)).orElseThrow());
        assertEquals(ImageFormat.JPEG, ImageFormat.detect(TestImages.jpeg(2, 2)).orElseThrow());
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(TestImages.webpWithMetadata("x")).orElseThrow());
        assertTrue(ImageFormat.detect("<html><script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII)).isEmpty());
    }

    @Test
    void truncatedFilesAreRejected() {
        byte[] jpeg = TestImages.jpeg(4, 4);
        byte[] truncated = Arrays.copyOf(jpeg, 30);
        assertThrows(IllegalArgumentException.class, () -> ImageMetadataStripper.strip(truncated, ImageFormat.JPEG));

        byte[] png = TestImages.png(4, 4);
        byte[] noEnd = Arrays.copyOf(png, png.length - 12);
        assertThrows(IllegalArgumentException.class, () -> ImageMetadataStripper.strip(noEnd, ImageFormat.PNG));
    }
}
