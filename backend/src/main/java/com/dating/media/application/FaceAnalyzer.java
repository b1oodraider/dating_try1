package com.dating.media.application;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
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
 * Finds faces on a photo (OpenCV YuNet) and turns each into a feature vector (SFace); without a face, looks for a
 * person at all (NanoDet). CPU only.
 * Vectors live only in memory for one comparison: no face templates are stored anywhere.
 */
@Component
public class FaceAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FaceAnalyzer.class);
    static final String DETECTOR = "face_detection_yunet_2023mar.onnx";
    static final String RECOGNIZER = "face_recognition_sface_2021dec.onnx";
    /** NanoDet-Plus (COCO): finds a person whose face is hidden (hair, back, far away). */
    static final String PERSON_DETECTOR = "object_detection_nanodet_2022nov.onnx";
    private static final int PERSON_INPUT = 416;
    private static final Scalar PERSON_MEAN = new Scalar(103.53, 116.28, 123.675);
    private static final Scalar PERSON_STD = new Scalar(57.375, 57.12, 58.395);
    /** Larger photos are scaled down first: faster, and YuNet finds faces of a profile photo at this size. */
    private static final int MAX_SIDE = 640;

    /**
     * @param features one L2-normalized vector per face found; empty = no face
     * @param person   a person is on the photo (a found face counts; otherwise the person detector decides)
     */
    public record Scan(List<float[]> features, boolean person) {
        public Scan(List<float[]> features) {
            this(features, !features.isEmpty());
        }

        public boolean hasFace() {
            return !features.isEmpty();
        }
    }

    private final FaceDetectorYN detector;
    private final FaceRecognizerSF recognizer;
    private final Net personDetector;
    private final float personThreshold;

    public FaceAnalyzer(FaceProperties properties) {
        if (properties.modelDir().isBlank()) {
            log.warn("app.faces.model-dir is not set: photos are not checked for faces");
            detector = null;
            recognizer = null;
            personDetector = null;
            personThreshold = 1;
            return;
        }
        Path dir = Path.of(properties.modelDir());
        for (String model : List.of(DETECTOR, RECOGNIZER, PERSON_DETECTOR)) {
            if (!Files.isRegularFile(dir.resolve(model))) {
                throw new IllegalStateException("Face model not found: " + dir.resolve(model));
            }
        }
        nu.pattern.OpenCV.loadLocally();
        detector = FaceDetectorYN.create(dir.resolve(DETECTOR).toString(), "", new Size(320, 320),
                properties.scoreThreshold(), 0.3f, 50);
        recognizer = FaceRecognizerSF.create(dir.resolve(RECOGNIZER).toString(), "");
        personDetector = Dnn.readNet(dir.resolve(PERSON_DETECTOR).toString());
        personThreshold = properties.personThreshold();
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
            return new Scan(features, !features.isEmpty() || hasPerson(img));
        } finally {
            img.release();
            faces.release();
        }
    }

    /** Best «person» score of NanoDet over the letterboxed photo (same preprocessing as the OpenCV Zoo demo). */
    private boolean hasPerson(Mat bgr) {
        Mat rgb = new Mat();
        Mat boxed = new Mat();
        Mat blob = null;
        List<Mat> outs = new ArrayList<>();
        try {
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
            double k = (double) PERSON_INPUT / Math.max(rgb.cols(), rgb.rows());
            int w = (int) (rgb.cols() * k);
            int h = (int) (rgb.rows() * k);
            Imgproc.resize(rgb, rgb, new Size(w, h), 0, 0, Imgproc.INTER_AREA);
            int left = (PERSON_INPUT - w) / 2;
            int top = (PERSON_INPUT - h) / 2;
            Core.copyMakeBorder(rgb, boxed, top, PERSON_INPUT - h - top, left, PERSON_INPUT - w - left,
                    Core.BORDER_CONSTANT, new Scalar(0, 0, 0));
            boxed.convertTo(boxed, CvType.CV_32FC3);
            Core.subtract(boxed, PERSON_MEAN, boxed);
            Core.divide(boxed, PERSON_STD, boxed);
            blob = Dnn.blobFromImage(boxed);
            personDetector.setInput(blob);
            personDetector.forward(outs, personDetector.getUnconnectedOutLayersNames());
            double best = 0;
            for (int i = 0; i < outs.size(); i += 2) { // class scores; odd outputs are boxes
                Mat scores = outs.get(i).reshape(1, (int) (outs.get(i).total() / 80));
                best = Math.max(best, Core.minMaxLoc(scores.col(0)).maxVal); // COCO class 0 = person
            }
            return best >= personThreshold;
        } finally {
            rgb.release();
            boxed.release();
            if (blob != null) {
                blob.release();
            }
            outs.forEach(Mat::release);
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
