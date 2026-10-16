package com.dating.support;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Builds small real images (and images with planted metadata) for tests.
 */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] png(int width, int height) {
        return encode(width, height, "png");
    }

    public static byte[] jpeg(int width, int height) {
        return encode(width, height, "jpg");
    }

    private static byte[] encode(int width, int height, String format) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF3366);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * Inserts a tEXt chunk right before IEND.
     */
    public static byte[] pngWithText(byte[] png, String text) {
        byte[] data = ("Comment\0" + text).getBytes(StandardCharsets.ISO_8859_1);
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);

        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        writeInt(chunk, data.length);
        chunk.writeBytes(type);
        chunk.writeBytes(data);
        writeInt(chunk, (int) crc.getValue());

        int iend = png.length - 12;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, iend);
        out.writeBytes(chunk.toByteArray());
        out.write(png, iend, 12);
        return out.toByteArray();
    }

    /**
     * Inserts after SOI an EXIF APP1 (Orientation + the secret as trailing payload, like GPS data)
     * and a COM segment containing the secret.
     */
    public static byte[] jpegWithExif(byte[] jpeg, int orientation, String secret) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.writeBytes(new byte[]{'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08});
        tiff.writeBytes(new byte[]{0x00, 0x01});
        tiff.writeBytes(new byte[]{0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00});
        tiff.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x00});
        tiff.writeBytes(secretBytes);

        ByteArrayOutputStream app1Data = new ByteArrayOutputStream();
        app1Data.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        app1Data.writeBytes(tiff.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI
        writeSegment(out, 0xE1, app1Data.toByteArray());
        writeSegment(out, 0xFE, secretBytes);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /**
     * Synthetic WebP container: VP8X (EXIF+XMP flags set), a dummy image chunk, EXIF and XMP chunks.
     * The stripper works on the RIFF structure only, so the image payload does not need to be decodable.
     */
    public static byte[] webpWithMetadata(String secret) {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        writeRiffChunk(chunks, "VP8X", new byte[]{0x0C, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        writeRiffChunk(chunks, "VP8L", new byte[]{0x2F, 0, 0, 0, 0});
        writeRiffChunk(chunks, "EXIF", secret.getBytes(StandardCharsets.US_ASCII));
        writeRiffChunk(chunks, "XMP ", (secret + "!").getBytes(StandardCharsets.US_ASCII));

        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeIntLe(out, 4 + body.length);
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(body);
        return out.toByteArray();
    }

    public static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    public static boolean contains(byte[] haystack, String ascii) {
        return contains(haystack, ascii.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeSegment(ByteArrayOutputStream out, int marker, byte[] data) {
        int length = data.length + 2;
        out.write(0xFF);
        out.write(marker);
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        out.writeBytes(data);
    }

    private static void writeRiffChunk(ByteArrayOutputStream out, String fourCc, byte[] data) {
        out.writeBytes(fourCc.getBytes(StandardCharsets.US_ASCII));
        writeIntLe(out, data.length);
        out.writeBytes(data);
        if ((data.length & 1) == 1) {
            out.write(0);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeIntLe(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
