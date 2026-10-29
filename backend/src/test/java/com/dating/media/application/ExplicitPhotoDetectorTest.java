package com.dating.media.application;

import com.dating.support.TestImages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the real open_nsfw model when FACE_MODELS_DIR points at it (CI downloads it). */
class ExplicitPhotoDetectorTest {

    private static final String MODELS = System.getenv("FACE_MODELS_DIR");
    private static final ExplicitPhotoDetector.Properties THRESHOLDS = new ExplicitPhotoDetector.Properties(0.85, 0.5);

    @Test
    void offWithoutTheModel() {
        ExplicitPhotoDetector detector = new ExplicitPhotoDetector(new FaceProperties("", 0.8f, 0.363, 0.5f), THRESHOLDS);
        assertFalse(detector.enabled());
        assertEquals(ExplicitPhotoDetector.Verdict.OK, detector.check(TestImages.png(8, 8)));
    }

    @Test
    void anOrdinaryPictureIsFineAndGarbageIsUnknown() {
        assumeTrue(MODELS != null, "FACE_MODELS_DIR is not set");
        ExplicitPhotoDetector detector =
                new ExplicitPhotoDetector(new FaceProperties(MODELS, 0.8f, 0.363, 0.5f), THRESHOLDS);
        assertTrue(detector.enabled());
        Double score = detector.score(TestImages.png(300, 400));
        assertNotNull(score);
        assertTrue(score < 0.5, "score " + score);
        assertEquals(ExplicitPhotoDetector.Verdict.OK, detector.check(TestImages.png(300, 400)));
        assertNull(detector.score(new byte[]{1, 2, 3}));
    }
}
