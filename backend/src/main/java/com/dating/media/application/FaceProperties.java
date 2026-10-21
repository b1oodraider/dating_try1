package com.dating.media.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Face check of uploaded photos (docs/trust-safety.md, «Фото»).
 *
 * @param modelDir       folder with face_detection_yunet_2023mar.onnx, face_recognition_sface_2021dec.onnx and
 *                       object_detection_nanodet_2022nov.onnx;
 *                       blank turns the check off (photos are accepted, new photos of verified people go to review)
 * @param scoreThreshold minimal detector confidence for a face
 * @param matchThreshold cosine similarity from which two faces are the same person (OpenCV's value for SFace)
 * @param personThreshold NanoDet «person» score from which a photo without a visible face still shows a person
 */
@ConfigurationProperties(prefix = "app.faces")
public record FaceProperties(@DefaultValue("") String modelDir,
                             @DefaultValue("0.8") float scoreThreshold,
                             @DefaultValue("0.363") double matchThreshold,
                             @DefaultValue("0.5") float personThreshold) {
}
