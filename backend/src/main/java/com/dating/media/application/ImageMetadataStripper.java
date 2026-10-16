package com.dating.media.application;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Removes metadata (EXIF incl. GPS coordinates, XMP, comments, text chunks) from uploaded images
 * without re-encoding pixels. For a dating service EXIF GPS is a direct location leak.
 * <p>
 * JPEG EXIF orientation is preserved in a minimal EXIF block, otherwise phone photos would show rotated.
 * Throws {@link IllegalArgumentException} for structurally broken files.
 */
public final class ImageMetadataStripper {

    private ImageMetadataStripper() {
    }

    public static byte[] strip(byte[] data, ImageFormat format) {
        return switch (format) {
            case JPEG -> stripJpeg(data);
            case PNG -> stripPng(data);
            case WEBP -> stripWebp(data);
        };
    }

    // ---------------------------------------------------------------- JPEG

    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int APP0 = 0xE0;
    private static final int APP1 = 0xE1;
    private static final int APP2 = 0xE2;   // ICC colour profile: kept, needed for correct colours
    private static final int APP14 = 0xEE;  // Adobe colour transform: kept
    private static final int COM = 0xFE;

    private static byte[] stripJpeg(byte[] data) {
        if (data.length < 4 || u8(data, 0) != 0xFF || u8(data, 1) != SOI) {
            throw new IllegalArgumentException("Not a JPEG");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(0xFF);
        out.write(SOI);

        Integer orientation = null;
        boolean exifWritten = false;
        int pos = 2;
        while (pos < data.length) {
            if (u8(data, pos) != 0xFF) {
                throw new IllegalArgumentException("Broken JPEG marker at " + pos);
            }
            while (pos + 1 < data.length && u8(data, pos + 1) == 0xFF) {
                pos++; // fill bytes
            }
            if (pos + 1 >= data.length) {
                throw new IllegalArgumentException("Truncated JPEG");
            }
            int marker = u8(data, pos + 1);

            if (marker == EOI) {
                out.write(data, pos, 2);
                return out.toByteArray();
            }
            if (marker == SOS) {
                if (!exifWritten && orientation != null) {
                    writeMinimalExif(out, orientation);
                }
                // Entropy-coded data follows; metadata segments do not appear after SOS in practice.
                out.write(data, pos, data.length - pos);
                return out.toByteArray();
            }
            if ((marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
                out.write(data, pos, 2);
                pos += 2;
                continue;
            }
            if (pos + 3 >= data.length) {
                throw new IllegalArgumentException("Truncated JPEG segment");
            }
            int length = (u8(data, pos + 2) << 8) | u8(data, pos + 3);
            int end = pos + 2 + length;
            if (length < 2 || end > data.length) {
                throw new IllegalArgumentException("Invalid JPEG segment length");
            }

            if (marker == APP1) {
                Integer o = readExifOrientation(data, pos + 4, end);
                if (o != null) {
                    orientation = o;
                }
            } else if (isKeptJpegSegment(marker)) {
                // Keep JFIF (APP0) first, then our minimal EXIF, then everything else.
                if (marker != APP0 && !exifWritten && orientation != null) {
                    writeMinimalExif(out, orientation);
                    exifWritten = true;
                }
                out.write(data, pos, end - pos);
            }
            pos = end;
        }
        throw new IllegalArgumentException("JPEG without image data");
    }

    private static boolean isKeptJpegSegment(int marker) {
        if (marker == APP1 || marker == COM) {
            return false;
        }
        if (marker >= APP0 && marker <= 0xEF) {
            return marker == APP0 || marker == APP2 || marker == APP14;
        }
        return true; // DQT, DHT, SOFn, DRI, ...
    }

    private static Integer readExifOrientation(byte[] data, int start, int end) {
        if (end - start < 14 || !"Exif".equals(new String(data, start, 4, StandardCharsets.US_ASCII))) {
            return null;
        }
        int tiff = start + 6;
        boolean littleEndian;
        if (u8(data, tiff) == 'I' && u8(data, tiff + 1) == 'I') {
            littleEndian = true;
        } else if (u8(data, tiff) == 'M' && u8(data, tiff + 1) == 'M') {
            littleEndian = false;
        } else {
            return null;
        }
        long ifdOffset = u32(data, tiff + 4, littleEndian);
        int ifd = tiff + (int) ifdOffset;
        if (ifdOffset < 8 || ifd + 2 > end) {
            return null;
        }
        int entries = u16(data, ifd, littleEndian);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) {
                return null;
            }
            if (u16(data, entry, littleEndian) == 0x0112) {
                int value = u16(data, entry + 8, littleEndian);
                return value >= 1 && value <= 8 ? value : null;
            }
        }
        return null;
    }

    /**
     * APP1 "Exif" with a single IFD0 entry: Orientation (0x0112, SHORT).
     */
    private static void writeMinimalExif(ByteArrayOutputStream out, int orientation) {
        if (orientation == 1) {
            return;
        }
        byte[] segment = {
                (byte) 0xFF, (byte) APP1, 0x00, 0x22,
                'E', 'x', 'i', 'f', 0x00, 0x00,
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x01,
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        out.write(segment, 0, segment.length);
    }

    // ---------------------------------------------------------------- PNG

    private static final Set<String> PNG_METADATA_CHUNKS = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");

    private static byte[] stripPng(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, 8);
        int pos = 8;
        boolean sawEnd = false;
        while (pos + 12 <= data.length) {
            long length = u32(data, pos, false);
            String type = new String(data, pos + 4, 4, StandardCharsets.US_ASCII);
            long chunkEnd = pos + 12 + length;
            if (length > Integer.MAX_VALUE || chunkEnd > data.length) {
                throw new IllegalArgumentException("Invalid PNG chunk length");
            }
            if (!PNG_METADATA_CHUNKS.contains(type)) {
                out.write(data, pos, (int) (chunkEnd - pos));
            }
            pos = (int) chunkEnd;
            if ("IEND".equals(type)) {
                sawEnd = true;
                break;
            }
        }
        if (!sawEnd) {
            throw new IllegalArgumentException("PNG without IEND");
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- WebP

    private static final int VP8X_FLAG_EXIF = 0x08;
    private static final int VP8X_FLAG_XMP = 0x04;

    private static byte[] stripWebp(byte[] data) {
        long riffSize = u32(data, 4, true);
        if (riffSize + 8 > data.length || riffSize < 4) {
            throw new IllegalArgumentException("Invalid RIFF size");
        }
        int limit = (int) (riffSize + 8);
        List<byte[]> kept = new ArrayList<>();
        int pos = 12;
        while (pos + 8 <= limit) {
            String fourCc = new String(data, pos, 4, StandardCharsets.US_ASCII);
            long size = u32(data, pos + 4, true);
            long padded = size + (size & 1);
            long chunkEnd = pos + 8 + padded;
            if (chunkEnd > limit) {
                throw new IllegalArgumentException("Invalid WebP chunk size");
            }
            if (!"EXIF".equals(fourCc) && !"XMP ".equals(fourCc)) {
                byte[] chunk = new byte[(int) (chunkEnd - pos)];
                System.arraycopy(data, pos, chunk, 0, chunk.length);
                if ("VP8X".equals(fourCc) && chunk.length > 8) {
                    chunk[8] = (byte) (chunk[8] & ~(VP8X_FLAG_EXIF | VP8X_FLAG_XMP));
                }
                kept.add(chunk);
            }
            pos = (int) chunkEnd;
        }

        int payload = 4 + kept.stream().mapToInt(c -> c.length).sum();
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload + 8);
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.write(payload & 0xFF);
        out.write((payload >>> 8) & 0xFF);
        out.write((payload >>> 16) & 0xFF);
        out.write((payload >>> 24) & 0xFF);
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        kept.forEach(out::writeBytes);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- helpers

    private static int u8(byte[] data, int pos) {
        if (pos < 0 || pos >= data.length) {
            throw new IllegalArgumentException("Unexpected end of image data");
        }
        return data[pos] & 0xFF;
    }

    private static int u16(byte[] data, int pos, boolean littleEndian) {
        return littleEndian
                ? u8(data, pos) | (u8(data, pos + 1) << 8)
                : (u8(data, pos) << 8) | u8(data, pos + 1);
    }

    private static long u32(byte[] data, int pos, boolean littleEndian) {
        long b0 = u8(data, pos), b1 = u8(data, pos + 1), b2 = u8(data, pos + 2), b3 = u8(data, pos + 3);
        return littleEndian
                ? b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
                : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }
}
