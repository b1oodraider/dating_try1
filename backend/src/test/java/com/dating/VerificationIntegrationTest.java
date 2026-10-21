package com.dating;

import com.dating.support.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Manual selfie verification with a random gesture; the badge, the lifted restriction and the deleted selfie. */
class VerificationIntegrationTest extends AdminTestSupport {

    record User(String token, UUID accountId, String profileId) {
    }

    @Test
    void selfieIsReviewedByHandAndApprovalGivesTheBadge() throws Exception {
        String city = uniqueCity();
        User anna = user(city, "Анна");
        User viewer = user(city, "Зритель");
        String moderator = moderator();

        // A fake-profile sanction hides her until she is verified.
        as(moderator, post("/api/v1/admin/accounts/" + anna.accountId() + "/restrictions"),
                "{\"kind\": \"VERIFICATION_REQUIRED\"}").andExpect(status().isCreated());
        discovery(viewer.token(), city, null, null, 50).andExpect(jsonPath("$.items[*].id", not(hasItem(anna.profileId()))));

        String first = startVerification(anna);
        as(anna.token(), post("/api/v1/me/verification"), "{\"faceComparisonConsent\": true}")
                .andExpect(jsonPath("$.id").value(first)); // the open request comes back, same gesture
        uploadSelfie(anna, first).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        as(anna.token(), post("/api/v1/me/verification"), "{\"faceComparisonConsent\": true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERIFICATION_PENDING"));

        as(anna.token(), get("/api/v1/admin/verifications"), null).andExpect(status().isForbidden());
        as(moderator, get("/api/v1/admin/verifications?limit=100"), null)
                .andExpect(jsonPath("$.items[*].id", hasItem(first)));
        as(moderator, get("/api/v1/admin/verifications/" + first), null)
                .andExpect(jsonPath("$.selfieAvailable").value(true))
                .andExpect(jsonPath("$.instruction").exists())
                .andExpect(jsonPath("$.profilePhotos.length()").value(1));
        as(moderator, get("/api/v1/admin/verifications/" + first + "/selfie"), null)
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        as(viewer.token(), get("/api/v1/admin/verifications/" + first + "/selfie"), null).andExpect(status().isForbidden());

        // A rejection needs a reason; the selfie is gone right after the decision.
        decide(moderator, first, "{\"approve\": false}").andExpect(status().isBadRequest());
        decide(moderator, first, "{\"approve\": false, \"reason\": \"Жест не виден\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.selfieAvailable").value(false));
        as(moderator, get("/api/v1/admin/verifications/" + first + "/selfie"), null).andExpect(status().isNotFound());
        as(anna.token(), get("/api/v1/me/verification"), null)
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.current.rejectReason").value("Жест не виден"));

        String second = startVerification(anna);
        uploadSelfie(anna, second).andExpect(status().isOk());
        decide(moderator, second, "{\"approve\": true}").andExpect(jsonPath("$.status").value("APPROVED"));
        decide(moderator, second, "{\"approve\": true}").andExpect(status().isConflict());

        as(anna.token(), get("/api/v1/me/verification"), null).andExpect(jsonPath("$.verified").value(true));
        as(anna.token(), get("/api/v1/me/restrictions"), null).andExpect(jsonPath("$.length()").value(0));
        discovery(viewer.token(), city, null, null, 50)
                .andExpect(jsonPath("$.items[?(@.id == '" + anna.profileId() + "')].verified", hasItem(true)));
        as(anna.token(), post("/api/v1/me/verification"), "{\"faceComparisonConsent\": true}").andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM verification_requests WHERE account_id = ? AND selfie_key IS NOT NULL""",
                Integer.class, anna.accountId()), "no selfie is kept after decisions");

        // A new photo keeps the badge (each photo is checked for a person instead, docs/TODO.md).
        uploadPng(anna.token());
        as(anna.token(), get("/api/v1/me/profile"), null).andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    void attemptsAreLimitedAndExpire() throws Exception {
        User bob = user(uniqueCity(), "Боб");
        for (int i = 0; i < 3; i++) {
            String id = startVerification(bob);
            jdbc.update("UPDATE verification_requests SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
            if (i == 0) {
                uploadSelfie(bob, id).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("VERIFICATION_EXPIRED"));
            }
        }
        as(bob.token(), post("/api/v1/me/verification"), "{\"faceComparisonConsent\": true}").andExpect(status().isTooManyRequests());
    }

    private String startVerification(User user) throws Exception {
        return readId(as(user.token(), post("/api/v1/me/verification"), "{\"faceComparisonConsent\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_SELFIE"))
                .andExpect(jsonPath("$.instruction").exists()));
    }

    private static String readId(ResultActions actions) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(body(actions.andReturn()), "$.id");
    }

    private ResultActions uploadSelfie(User user, String requestId) throws Exception {
        return mvc.perform(multipart("/api/v1/me/verification/" + requestId + "/selfie")
                .file(new MockMultipartFile("file", "selfie.png", "image/png", TestImages.png(4, 4)))
                .header("Authorization", bearer(user.token())));
    }

    private ResultActions decide(String moderator, String requestId, String body) throws Exception {
        return as(moderator, post("/api/v1/admin/verifications/" + requestId + "/decision"), body);
    }

    private User user(String city, String name) throws Exception {
        String token = login(newPhone());
        publishWith(token, name, city, List.of());
        return new User(token, UUID.fromString(accountId(token)), json(get("/api/v1/me/profile"), token, "$.id"));
    }

    private String moderator() throws Exception {
        String token = login(newPhone());
        admin(put("/api/v1/admin/accounts/" + accountId(token) + "/roles/MODERATOR"), null).andExpect(status().isOk());
        return token;
    }
}
