package com.dating.media.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Face check of uploaded photos (docs/trust-safety.md, «Фото»).
 *
 * @param modelDir       folder with face_detection_yunet_2023mar.onnx and face_recognition_sface_2021dec.onnx;
 *                       blank turns the check off (photos are accepted, new photos of verified people go to review)
 * @param scoreThreshold minimal detector confidence for a face
 * @param matchThreshold cosine similarity from which two faces are the same person (OpenCV's value for SFace)
 */
@ConfigurationProperties(prefix = "app.faces")
public record FaceProperties(@DefaultValue("") String modelDir,
                             @DefaultValue("0.8") float scoreThreshold,
                             @DefaultValue("0.363") double matchThreshold) {
}
