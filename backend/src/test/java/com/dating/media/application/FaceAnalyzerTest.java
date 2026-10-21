package com.dating.media.application;

import com.dating.support.TestImages;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real YuNet + SFace models when FACE_MODELS_DIR points at them (CI downloads them).
 * FACE_SAMPLES_DIR with lena.jpg and messi5.jpg (OpenCV samples, not committed) adds the same/other person check.
 */
class FaceAnalyzerTest {

    private static final String MODELS = System.getenv("FACE_MODELS_DIR");
    private static final String SAMPLES = System.getenv("FACE_SAMPLES_DIR");

    @Test
    void offWithoutModels() {
        FaceAnalyzer analyzer = new FaceAnalyzer(new FaceProperties("", 0.8f, 0.363, 0.5f));
        assertFalse(analyzer.enabled());
        assertNull(analyzer.scan(TestImages.png(4, 4)));
    }

    @Test
    void picturesWithoutPeopleHaveNoFace() {
        assumeTrue(MODELS != null, "FACE_MODELS_DIR is not set");
        FaceAnalyzer analyzer = new FaceAnalyzer(new FaceProperties(MODELS, 0.8f, 0.363, 0.5f));
        FaceAnalyzer.Scan scan = analyzer.scan(TestImages.png(64, 64));
        assertNotNull(scan);
        assertFalse(scan.hasFace());
        assertFalse(scan.person());
        assertNull(analyzer.scan(new byte[]{1, 2, 3}), "undecodable is «unknown», not «no face»");
    }

    @Test
    void samePersonMatchesAndAnotherDoesNot() throws Exception {
        assumeTrue(MODELS != null && SAMPLES != null, "FACE_MODELS_DIR / FACE_SAMPLES_DIR are not set");
        FaceAnalyzer analyzer = new FaceAnalyzer(new FaceProperties(MODELS, 0.8f, 0.363, 0.5f));
        byte[] lena = Files.readAllBytes(Path.of(SAMPLES, "lena.jpg"));
        byte[] messi = Files.readAllBytes(Path.of(SAMPLES, "messi5.jpg"));

        FaceAnalyzer.Scan a = analyzer.scan(lena);
        FaceAnalyzer.Scan b = analyzer.scan(mirroredAndScaled(lena));
        FaceAnalyzer.Scan c = analyzer.scan(messi);
        assertTrue(a.hasFace() && b.hasFace() && c.hasFace());
        double same = FaceAnalyzer.similarity(a, b);
        double other = FaceAnalyzer.similarity(a, c);
        assertTrue(same >= 0.363, "same person: " + same);
        assertTrue(other < 0.363, "another person: " + other);
    }

    /** person-without-face.* in FACE_SAMPLES_DIR: a person whose face is hidden (hair, back) still counts. */
    @Test
    void personWithoutVisibleFace() throws Exception {
        assumeTrue(MODELS != null && SAMPLES != null, "FACE_MODELS_DIR / FACE_SAMPLES_DIR are not set");
        Path sample;
        try (var files = Files.list(Path.of(SAMPLES))) {
            sample = files.filter(p -> p.getFileName().toString().startsWith("person-without-face.")).findFirst().orElse(null);
        }
        assumeTrue(sample != null, "no person-without-face sample");
        FaceAnalyzer.Scan scan = new FaceAnalyzer(new FaceProperties(MODELS, 0.8f, 0.363, 0.5f))
                .scan(Files.readAllBytes(sample));
        System.out.println("person-without-face: faces=" + scan.features().size() + " person=" + scan.person());
        assertTrue(scan.person());
    }

    /** no-person*.* in FACE_SAMPLES_DIR: pictures without people (food, animals, buildings) are not a person. */
    @Test
    void picturesOfThingsAreNotAPerson() throws Exception {
        assumeTrue(MODELS != null && SAMPLES != null, "FACE_MODELS_DIR / FACE_SAMPLES_DIR are not set");
        FaceAnalyzer analyzer = new FaceAnalyzer(new FaceProperties(MODELS, 0.8f, 0.363, 0.5f));
        try (var files = Files.list(Path.of(SAMPLES))) {
            for (Path sample : files.filter(p -> p.getFileName().toString().startsWith("no-person")).toList()) {
                FaceAnalyzer.Scan scan = analyzer.scan(Files.readAllBytes(sample));
                assertFalse(scan.person(), sample.getFileName() + " is not a person");
            }
        }
    }

    private static byte[] mirroredAndScaled(byte[] jpeg) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(jpeg));
        int w = (int) (src.getWidth() * 0.7);
        int h = (int) (src.getHeight() * 0.7);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, w, 0, -w, h, null); // mirrored
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "jpg", bytes);
        return bytes.toByteArray();
    }
}
