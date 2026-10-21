package com.dating;

import com.dating.support.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verification as a gate: unverified are hidden, see a few profiles a day, and their likes wait for approval. */
@TestPropertySource(properties = {"app.verification-gate.enabled=true", "app.verification-gate.daily-views=3"})
class VerificationGateIntegrationTest extends AdminTestSupport {

    record User(String token, UUID accountId, String profileId) {
    }

    @Test
    void unverifiedProfilesAreHiddenAndUnverifiedViewersSeeAFewADay() throws Exception {
        String city = uniqueCity();
        user(city, "Анна", true);
        user(city, "Вера", true);
        user(city, "Галя", true);
        user(city, "Даша", true);
        user(city, "Ева", false);

        User verified = user(city, "Зритель", true);
        feed(verified, city).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4));

        User newcomer = user(city, "Новичок", false);
        feed(newcomer, city).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(3)));
        feed(newcomer, city).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DAILY_VIEWS_LIMIT"));
    }

    @Test
    void likesOfUnverifiedWaitAndGoOutOnApproval() throws Exception {
        String city = uniqueCity();
        User boris = user(city, "Борис", true);
        User anna = user(city, "Анна", false);
        User carol = user(city, "Карина", true);

        like(anna, boris).andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(true));
        like(anna, carol).andExpect(jsonPath("$.queued").value(true));
        as(anna.token(), post("/api/v1/discovery/profiles/" + boris.profileId() + "/superlike"),
                "{\"idempotencyKey\": \"k1\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_REQUIRED"));
        receivedCount(boris).andExpect(jsonPath("$.count").value(0));
        feed(anna, city).andExpect(jsonPath("$.totalElements").value(0)); // liked profiles leave her feed

        // Carol likes Anna back while Anna waits: still no match before approval.
        like(carol, anna).andExpect(jsonPath("$.matched").value(false));

        approve(anna);
        receivedCount(boris).andExpect(jsonPath("$.count").value(1));
        as(anna.token(), get("/api/v1/matches"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].profile.displayName", contains("Карина")));
        like(anna, boris).andExpect(jsonPath("$.queued").doesNotExist()); // verified now: a normal like
    }

    @Test
    void queuedLikeDiesAfterADayButReviewTimeDoesNotCount() throws Exception {
        String city = uniqueCity();
        User old = user(city, "Старый", true);
        User paused = user(city, "Пауза", true);
        User anna = user(city, "Анна", false);
        like(anna, old).andExpect(jsonPath("$.queued").value(true));
        like(anna, paused).andExpect(jsonPath("$.queued").value(true));
        jdbc.update("UPDATE queued_likes SET liked_at = now() - interval '25 hours' WHERE actor_account_id = ?",
                anna.accountId());

        String requestId = submitSelfie(anna);
        // The review took 2 hours: only the like made 25 h ago survives if those 2 h are not counted...
        jdbc.update("UPDATE verification_requests SET submitted_at = now() - interval '2 hours' WHERE id = ?::uuid",
                requestId);
        // ...and «Старый» was liked 27 h ago, before the clock could be paused.
        jdbc.update("""
                UPDATE queued_likes SET liked_at = now() - interval '27 hours'
                 WHERE actor_account_id = ? AND target_account_id = ?""", anna.accountId(), old.accountId());
        decide(requestId);

        receivedCount(paused).andExpect(jsonPath("$.count").value(1));
        receivedCount(old).andExpect(jsonPath("$.count").value(0));
    }

    private User user(String city, String name, boolean verified) throws Exception {
        String token = login(newPhone());
        publishWith(token, name, city, List.of());
        UUID accountId = UUID.fromString(accountId(token));
        if (verified) {
            jdbc.update("UPDATE profiles SET verified_at = now() WHERE account_id = ?", accountId);
        }
        return new User(token, accountId, json(get("/api/v1/me/profile"), token, "$.id"));
    }

    private ResultActions feed(User viewer, String city) throws Exception {
        canSearch(viewer.token());
        return mvc.perform(get("/api/v1/discovery/profiles").queryParam("city", city)
                .header("Authorization", bearer(viewer.token())));
    }

    private ResultActions like(User from, User to) throws Exception {
        return as(from.token(), post("/api/v1/discovery/profiles/" + to.profileId() + "/like"), null);
    }

    private ResultActions receivedCount(User user) throws Exception {
        return as(user.token(), get("/api/v1/me/likes/received/count"), null).andExpect(status().isOk());
    }

    private String submitSelfie(User user) throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(body(as(user.token(), post("/api/v1/me/verification"), null)
                .andExpect(status().isOk()).andReturn()), "$.id");
        mvc.perform(multipart("/api/v1/me/verification/" + id + "/selfie")
                        .file(new MockMultipartFile("file", "selfie.png", "image/png", TestImages.png(4, 4)))
                        .header("Authorization", bearer(user.token())))
                .andExpect(status().isOk());
        return id;
    }

    private void decide(String requestId) throws Exception {
        String moderator = login(newPhone());
        admin(put("/api/v1/admin/accounts/" + accountId(moderator) + "/roles/MODERATOR"), null).andExpect(status().isOk());
        as(moderator, post("/api/v1/admin/verifications/" + requestId + "/decision"), "{\"approve\": true}")
                .andExpect(status().isOk());
    }

    private void approve(User user) throws Exception {
        decide(submitSelfie(user));
    }
}
