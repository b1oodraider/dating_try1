package com.dating.media.application;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How explicit a photo is (docs/trust-safety.md, «Фото»): Yahoo open_nsfw (ResNet-50 1/2, BSD-2), CPU only, in the
 * folder of the face models. Above {@code rejectAbove} an upload is refused; above {@code reviewAbove} the photo
 * waits for a moderator.
 */
@Component
public class ExplicitPhotoDetector {

    /**
     * @param rejectAbove score from which the photo is refused right away
     * @param reviewAbove score from which a moderator decides (the photo is hidden until then)
     */
    @ConfigurationProperties(prefix = "app.explicit-photos")
    public record Properties(@DefaultValue("0.85") double rejectAbove, @DefaultValue("0.5") double reviewAbove) {
    }

    public enum Verdict { OK, REVIEW, REJECT }

    private static final Logger log = LoggerFactory.getLogger(ExplicitPhotoDetector.class);
    static final String MODEL = "resnet_50_1by2_nsfw.caffemodel";
    static final String CONFIG = "open_nsfw_deploy.prototxt";
    private static final Scalar MEAN = new Scalar(104, 117, 123);

    private final Net net;
    private final Properties properties;

    public ExplicitPhotoDetector(FaceProperties faces, Properties properties) {
        this.properties = properties;
        Path dir = faces.modelDir().isBlank() ? null : Path.of(faces.modelDir());
        if (dir == null || !Files.isRegularFile(dir.resolve(MODEL)) || !Files.isRegularFile(dir.resolve(CONFIG))) {
            // Not fatal like the face models: older servers get the files with the next install.sh run.
            log.warn("Explicit photo model not found in app.faces.model-dir: photos are not checked for nudity");
            net = null;
            return;
        }
        nu.pattern.OpenCV.loadLocally();
        net = Dnn.readNetFromCaffe(dir.resolve(CONFIG).toString(), dir.resolve(MODEL).toString());
    }

    public boolean enabled() {
        return net != null;
    }

    /** @return OK when the check is off or the image cannot be decoded (other checks still apply) */
    public Verdict check(byte[] image) {
        Double score = score(image);
        if (score == null) {
            return Verdict.OK;
        }
        return score >= properties.rejectAbove() ? Verdict.REJECT
                : score >= properties.reviewAbove() ? Verdict.REVIEW : Verdict.OK;
    }

    /** Probability that the photo is explicit, 0..1; null when off or undecodable. */
    synchronized Double score(byte[] image) { // OpenCV nets are not thread-safe
        if (net == null) {
            return null;
        }
        Mat img = Imgcodecs.imdecode(new MatOfByte(image), Imgcodecs.IMREAD_COLOR);
        Mat resized = new Mat();
        Mat blob = null;
        Mat prob = null;
        try {
            if (img.empty()) {
                return null;
            }
            // The model's preprocessing: 256×256, centre crop 224, BGR minus the ImageNet mean.
            Imgproc.resize(img, resized, new Size(256, 256), 0, 0, Imgproc.INTER_LINEAR);
            Mat crop = resized.submat(new Rect(16, 16, 224, 224));
            blob = Dnn.blobFromImage(crop, 1.0, new Size(224, 224), MEAN, false, false);
            net.setInput(blob);
            prob = net.forward("prob");
            return Math.max(0, Math.min(1, prob.get(0, 1)[0]));
        } finally {
            img.release();
            resized.release();
            if (blob != null) {
                blob.release();
            }
            if (prob != null) {
                prob.release();
            }
        }
    }
}
