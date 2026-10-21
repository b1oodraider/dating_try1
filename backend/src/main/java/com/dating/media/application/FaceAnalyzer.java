package com.dating.media.application;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds faces on a photo (OpenCV YuNet) and turns each into a feature vector (SFace), on the CPU.
 * Vectors live only in memory for one comparison: no face templates are stored anywhere.
 */
@Component
public class FaceAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FaceAnalyzer.class);
    static final String DETECTOR = "face_detection_yunet_2023mar.onnx";
    static final String RECOGNIZER = "face_recognition_sface_2021dec.onnx";
    /** Larger photos are scaled down first: faster, and YuNet finds faces of a profile photo at this size. */
    private static final int MAX_SIDE = 640;

    /** @param features one L2-normalized vector per face found; empty = no face */
    public record Scan(List<float[]> features) {
        public boolean hasFace() {
            return !features.isEmpty();
        }
    }

    private final FaceDetectorYN detector;
    private final FaceRecognizerSF recognizer;

    public FaceAnalyzer(FaceProperties properties) {
        if (properties.modelDir().isBlank()) {
            log.warn("app.faces.model-dir is not set: photos are not checked for faces");
            detector = null;
            recognizer = null;
            return;
        }
        Path dir = Path.of(properties.modelDir());
        for (String model : List.of(DETECTOR, RECOGNIZER)) {
            if (!Files.isRegularFile(dir.resolve(model))) {
                throw new IllegalStateException("Face model not found: " + dir.resolve(model));
            }
        }
        nu.pattern.OpenCV.loadLocally();
        detector = FaceDetectorYN.create(dir.resolve(DETECTOR).toString(), "", new Size(320, 320),
                properties.scoreThreshold(), 0.3f, 50);
        recognizer = FaceRecognizerSF.create(dir.resolve(RECOGNIZER).toString(), "");
    }

    public boolean enabled() {
        return detector != null;
    }

    /**
     * @return null when the check is off or OpenCV cannot decode the format (the caller must not treat that
     * as «no face»)
     */
    public synchronized Scan scan(byte[] image) { // the OpenCV models are not thread-safe
        if (detector == null) {
            return null;
        }
        Mat img = Imgcodecs.imdecode(new MatOfByte(image), Imgcodecs.IMREAD_COLOR);
        Mat faces = new Mat();
        try {
            if (img.empty()) {
                return null;
            }
            double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(img.cols(), img.rows()));
            if (scale < 1.0) {
                Imgproc.resize(img, img, new Size(Math.round(img.cols() * scale), Math.round(img.rows() * scale)),
                        0, 0, Imgproc.INTER_AREA);
            }
            detector.setInputSize(img.size());
            detector.detect(img, faces);
            List<float[]> features = new ArrayList<>();
            for (int i = 0; i < faces.rows(); i++) {
                Mat aligned = new Mat();
                Mat feature = new Mat();
                try {
                    recognizer.alignCrop(img, faces.row(i), aligned);
                    recognizer.feature(aligned, feature);
                    float[] vector = new float[(int) feature.total()];
                    feature.get(0, 0, vector);
                    features.add(normalized(vector));
                } finally {
                    aligned.release();
                    feature.release();
                }
            }
            return new Scan(features);
        } finally {
            img.release();
            faces.release();
        }
    }

    /** Best cosine similarity between any face of {@code a} and any face of {@code b} (group photos). */
    public static double similarity(Scan a, Scan b) {
        double best = -1;
        for (float[] x : a.features()) {
            for (float[] y : b.features()) {
                double dot = 0;
                for (int i = 0; i < x.length; i++) {
                    dot += x[i] * y[i];
                }
                best = Math.max(best, dot);
            }
        }
        return best;
    }

    private static float[] normalized(float[] v) {
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        float inv = norm == 0 ? 0 : (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
        return v;
    }
}
