package com.dating;

import com.dating.media.application.FaceAnalyzer;
import com.dating.support.TestImages;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Photos must show a face; a verified person's new photo that does not match the reference photos waits for a
 * moderator. The analyzer is stubbed: the PNG width is «who» is on it (1 = nobody). Real models: FaceAnalyzerTest.
 */
class PhotoFaceCheckIntegrationTest extends AdminTestSupport {

    @MockitoBean
    FaceAnalyzer faces;

    record User(String token, UUID accountId, String profileId) {
    }

    @BeforeEach
    void stubFaces() {
        when(faces.scan(any())).thenAnswer(call -> {
            byte[] png = call.getArgument(0);
            int width = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
            if (width == 1) {
                return new FaceAnalyzer.Scan(List.of());
            }
            float[] person = new float[128];
            person[width % 128] = 1;
            return new FaceAnalyzer.Scan(List.of(person));
        });
    }

    @Test
    void aPhotoWithoutAFaceIsRefused() throws Exception {
        String token = login(newPhone());
        upload(token, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_FACE_ON_PHOTO"));
        upload(token, 3).andExpect(status().isOk()).andExpect(jsonPath("$.underReview").value(false));
    }

    @Test
    void verificationNeedsConsent() throws Exception {
        String token = login(newPhone());
        as(token, post("/api/v1/me/verification"), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
    }

    @Test
    void newPhotosOfAVerifiedPersonAreComparedWithTheReference() throws Exception {
        String city = uniqueCity();
        User anna = user(city, "Анна"); // her published photo shows «person 3»
        verify(anna);
        User viewer = user(city, "Зритель");

        upload(anna.token(), 3).andExpect(status().isOk()).andExpect(jsonPath("$.underReview").value(false));
        String stranger = id(upload(anna.token(), 5).andExpect(jsonPath("$.underReview").value(true)));
        String another = id(upload(anna.token(), 6).andExpect(jsonPath("$.underReview").value(true)));

        // Hidden from others, shown to her with the mark.
        as(anna.token(), get("/api/v1/me/profile"), null)
                .andExpect(jsonPath("$.photos", hasSize(4)))
                .andExpect(jsonPath("$.photos[?(@.underReview == true)]", hasSize(2)));
        photosSeenBy(viewer, city).andExpect(jsonPath("$.items[0].photos", hasSize(2)));

        String moderator = moderator();
        as(anna.token(), get("/api/v1/admin/photo-reviews"), null).andExpect(status().isForbidden());
        as(moderator, get("/api/v1/admin/photo-reviews?limit=50"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.photoId == '" + stranger + "')].references[*]", hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.photoId == '" + stranger + "')].displayName", contains("Анна")));

        decide(moderator, stranger, false).andExpect(status().isNoContent());
        decide(moderator, another, true).andExpect(status().isNoContent());
        decide(moderator, another, true).andExpect(status().isConflict());

        photosSeenBy(viewer, city).andExpect(jsonPath("$.items[0].photos", hasSize(3)));
        // The approved photo is a reference now: the same «person 6» passes on its own.
        upload(anna.token(), 6).andExpect(jsonPath("$.underReview").value(false));
    }

    private ResultActions upload(String token, int person) throws Exception {
        return mvc.perform(multipart("/api/v1/me/profile/photos")
                .file(new MockMultipartFile("file", "p.png", "image/png", TestImages.png(person, 3)))
                .header("Authorization", bearer(token)));
    }

    private static String id(ResultActions actions) throws Exception {
        return JsonPath.read(body(actions.andReturn()), "$.id");
    }

    private ResultActions photosSeenBy(User viewer, String city) throws Exception {
        canSearch(viewer.token());
        return mvc.perform(get("/api/v1/discovery/profiles").queryParam("city", city).queryParam("limit", "50")
                .header("Authorization", bearer(viewer.token()))).andExpect(status().isOk());
    }

    private User user(String city, String name) throws Exception {
        String token = login(newPhone());
        publishWith(token, name, city, List.of());
        return new User(token, UUID.fromString(accountId(token)), json(get("/api/v1/me/profile"), token, "$.id"));
    }

    private void verify(User user) throws Exception {
        String requestId = JsonPath.read(body(as(user.token(), post("/api/v1/me/verification"),
                "{\"faceComparisonConsent\": true}").andExpect(status().isOk()).andReturn()), "$.id");
        mvc.perform(multipart("/api/v1/me/verification/" + requestId + "/selfie")
                        .file(new MockMultipartFile("file", "selfie.png", "image/png", TestImages.png(4, 4)))
                        .header("Authorization", bearer(user.token())))
                .andExpect(status().isOk());
        as(moderator(), post("/api/v1/admin/verifications/" + requestId + "/decision"), "{\"approve\": true}")
                .andExpect(status().isOk());
    }

    private ResultActions decide(String moderator, String photoId, boolean approve) throws Exception {
        return as(moderator, post("/api/v1/admin/photo-reviews/" + photoId + "/decision"),
                "{\"approve\": " + approve + "}");
    }

    private String moderator() throws Exception {
        String token = login(newPhone());
        admin(put("/api/v1/admin/accounts/" + accountId(token) + "/roles/MODERATOR"), null).andExpect(status().isOk());
        return token;
    }
}
