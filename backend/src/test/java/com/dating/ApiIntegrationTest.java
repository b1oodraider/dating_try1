package com.dating;

import com.dating.support.TestImages;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end checks of the defects found in review, against a real PostgreSQL (Testcontainers).
 */
class ApiIntegrationTest extends IntegrationTestBase {

    // ------------------------------------------------------------------ auth

    @Test
    void verifyIgnoresClientSuppliedPhone_noAccountTakeover() throws Exception {
        String victimPhone = newPhone();
        String victimToken = login(victimPhone);
        String victimAccountId = json(get("/api/v1/me"), victimToken, "$.id");

        String attackerPhone = newPhone();
        String challengeId = requestOtp(attackerPhone);
        MvcResult result = mvc.perform(post("/api/v1/auth/otp/verify")
                        .queryParam("phone", victimPhone)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(challengeId, MOCK_CODE)))
                .andExpect(status().isOk())
                .andReturn();

        String attackerAccountId = JsonPath.read(body(result), "$.accountId");
        assertNotEquals(victimAccountId, attackerAccountId);
    }

    @Test
    void differentSpellingsOfOnePhoneLogIntoOneAccount() throws Exception {
        String digits = newPhoneDigits();
        String first = json(get("/api/v1/me"), login("+7 (" + digits.substring(0, 3) + ") " + digits.substring(3)), "$.id");
        Thread.sleep(1100); // resend cooldown in the test profile
        String second = json(get("/api/v1/me"), login("8" + digits), "$.id");
        assertEquals(first, second);
    }

    @Test
    void failedAttemptsArePersistedAndCodeCannotBeBruteForced() throws Exception {
        String challengeId = requestOtp(newPhone());

        for (int i = 1; i <= 4; i++) {
            verify(challengeId, "000000").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }
        verify(challengeId, "000000").andExpect(jsonPath("$.code").value("MAX_ATTEMPTS_EXCEEDED"));
        // Even the right code is refused now: the counter survived the error responses.
        verify(challengeId, MOCK_CODE).andExpect(jsonPath("$.code").value("MAX_ATTEMPTS_EXCEEDED"));

        Integer attempts = jdbc.queryForObject("select attempt_count from auth_challenges where id = ?",
                Integer.class, UUID.fromString(challengeId));
        assertEquals(5, attempts);
    }

    @Test
    void challengeIsSingleUse() throws Exception {
        String challengeId = requestOtp(newPhone());
        verify(challengeId, MOCK_CODE).andExpect(status().isOk());
        verify(challengeId, MOCK_CODE).andExpect(jsonPath("$.code").value("CHALLENGE_ALREADY_USED"));
    }

    @Test
    void otpResendIsRateLimited() throws Exception {
        String phone = newPhone();
        requestOtp(phone);
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content(phoneBody(phone)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void invalidPhoneIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content(phoneBody("12-34")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHONE"));
    }

    @Test
    void logoutRevokesToken() throws Exception {
        String token = login(newPhone());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(token))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ errors

    @Test
    void unauthenticatedRequestsGetJson401() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().exists("X-Trace-Id"));
        mvc.perform(get("/api/v1/discovery/profiles")).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedRequestsGet400Not500() throws Exception {
        mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        String token = login(newPhone());
        mvc.perform(get("/api/v1/discovery/profiles").queryParam("minAge", "abc").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/discovery/profiles").queryParam("tagLogic", "XOR").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ profile

    @Test
    void newProfileIsPersistedEmptyAndCannotBePublished() throws Exception {
        String token = login(newPhone());

        String firstId = json(get("/api/v1/me/profile"), token, "$.id");
        mvc.perform(get("/api/v1/me/profile").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.displayName").value(nullValue()))
                .andExpect(jsonPath("$.age").value(nullValue()))
                .andExpect(jsonPath("$.missingForPublish", contains("displayName", "age", "city", "photos")));

        mvc.perform(post("/api/v1/me/profile/publish").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"))
                .andExpect(jsonPath("$.fieldErrors.photos").exists());
    }

    @Test
    void profileUpdateValidatesAfterTrimmingAndRejectsUnknownTags() throws Exception {
        String token = login(newPhone());

        putProfile(token, "{\"displayName\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.displayName").exists());
        putProfile(token, "{\"age\":17}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.age").exists());
        putProfile(token, "{\"tagCodes\":[\"PACE_ASYNCHRONOUS\",\"NO_SUCH_TAG\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_TAG"));

        putProfile(token, "{\"displayName\":\"  Анна \",\"age\":26,\"city\":\"Москва\",\"dailyStatusText\":\"Иду на выставку\",\"tagCodes\":[\"PACE_ASYNCHRONOUS\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Анна"))
                .andExpect(jsonPath("$.dailyStatusText").value("Иду на выставку"))
                .andExpect(jsonPath("$.dailyStatusExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.tags[0].code").value("PACE_ASYNCHRONOUS"))
                .andExpect(jsonPath("$.missingForPublish", contains("photos")));
    }

    // ------------------------------------------------------------------ photos

    @Test
    void uploadedPhotoIsStrippedAndServedBySignedUrlOnly() throws Exception {
        String token = login(newPhone());
        byte[] png = TestImages.pngWithText(TestImages.png(6, 6), "SECRET-LOCATION");

        MvcResult upload = mvc.perform(multipart("/api/v1/me/profile/photos")
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", png)) // lying name/type
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mimeType").value("image/png"))
                .andExpect(jsonPath("$.isPrimary").value(true))
                .andReturn();
        String url = JsonPath.read(body(upload), "$.url");

        MvcResult image = mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        byte[] served = image.getResponse().getContentAsByteArray();
        assertTrue(served.length > 0);
        assertFalse(TestImages.contains(served, "SECRET-LOCATION"), "metadata must be stripped");

        String tampered = url.replaceAll("sig=.", "sig=0");
        if (tampered.equals(url)) {
            tampered = url.replaceAll("sig=.", "sig=1");
        }
        mvc.perform(get(tampered)).andExpect(status().isNotFound());
    }

    @Test
    void nonImagesAndPhotoLimitAreRejected() throws Exception {
        String token = login(newPhone());
        mvc.perform(multipart("/api/v1/me/profile/photos")
                        .file(new MockMultipartFile("file", "x.png", "image/png", "<html>hi</html>".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnsupportedMediaType());

        for (int i = 0; i < 6; i++) {
            uploadPng(token);
        }
        mvc.perform(multipart("/api/v1/me/profile/photos")
                        .file(new MockMultipartFile("file", "p.png", "image/png", TestImages.png(2, 2)))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHOTO_LIMIT_EXCEEDED"));
    }

    @Test
    void deletingLastPhotoUnpublishesProfile() throws Exception {
        String token = login(newPhone());
        String photoId = publishWith(token, "Ольга", uniqueCity(), List.of());

        mvc.perform(delete("/api/v1/me/profile/photos/" + photoId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/profile").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.publicationStatus").value("DRAFT"))
                .andExpect(jsonPath("$.photos", hasSize(0)));
    }

    // ------------------------------------------------------------------ discovery

    @Test
    void discoveryFiltersTagsInSqlBeforePagination() throws Exception {
        String city = uniqueCity();
        publishWith(login(newPhone()), "Анна", city, List.of("PACE_ASYNCHRONOUS", "FORMAT_WALK_FIRST"));
        publishWith(login(newPhone()), "Борис", city, List.of("PACE_ASYNCHRONOUS"));
        publishWith(login(newPhone()), "Вера", city, List.of("FORMAT_WALK_FIRST"));
        String viewer = login(newPhone());
        publishWith(viewer, "Зритель", city, List.of("PACE_ASYNCHRONOUS", "FORMAT_WALK_FIRST"));

        discovery(viewer, city, "PACE_ASYNCHRONOUS", "OR", 20)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("Анна", "Борис")));

        discovery(viewer, city, "PACE_ASYNCHRONOUS,FORMAT_WALK_FIRST", "AND", 20)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("Анна"))
                .andExpect(jsonPath("$.items[0].accountId").doesNotExist())
                .andExpect(jsonPath("$.items[0].photos[0].url").value(startsWith("/api/v1/media/photos/")));

        // Exact totals with a small page; the viewer's own profile is excluded.
        discovery(viewer, city.toUpperCase(), "PACE_ASYNCHRONOUS,FORMAT_WALK_FIRST", "OR", 1)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    // ------------------------------------------------------------------ account deletion

    @Test
    void deletedAccountLosesAccessDisappearsAndPhoneCanRegisterAgain() throws Exception {
        String city = uniqueCity();
        String phone = newPhone();
        String token = login(phone);
        String oldAccountId = json(get("/api/v1/me"), token, "$.id");
        publishWith(token, "Удаляемый", city, List.of());
        String photoUrl = json(get("/api/v1/me/profile"), token, "$.photos[0].url");

        String viewer = login(newPhone());
        discovery(viewer, city, null, null, 20).andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(delete("/api/v1/me").header("Authorization", bearer(token))).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
        discovery(viewer, city, null, null, 20).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(photoUrl)).andExpect(status().isNotFound());

        Thread.sleep(1100); // resend cooldown in the test profile
        String newToken = login(phone);
        String newAccountId = json(get("/api/v1/me"), newToken, "$.id");
        assertNotEquals(oldAccountId, newAccountId);
        mvc.perform(get("/api/v1/me/profile").header("Authorization", bearer(newToken)))
                .andExpect(jsonPath("$.displayName").value(nullValue()));
    }

    // ------------------------------------------------------------------ exclusion words

    @Test
    void exclusionWordsAreNormalizedAndValidated() throws Exception {
        String token = login(newPhone());

        putWords(token, "[\"Курю\", \"курю\", \" Кальян \"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.words", contains("курю", "кальян")))
                .andExpect(jsonPath("$.maxWords").value(20));

        putWords(token, "[\"a\"]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.words").exists());
        putWords(token, "[\"два слова\"]").andExpect(status().isBadRequest());
        putWords(token, "[\"кальян|курю\"]").andExpect(status().isBadRequest());
        String tooMany = IntStream.rangeClosed(0, 20).mapToObj(i -> "\"w" + i + "\"").collect(Collectors.joining(","));
        putWords(token, "[" + tooMany + "]").andExpect(status().isBadRequest());

        // Failed updates do not touch the stored list.
        mvc.perform(get("/api/v1/me/exclusion-words").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.words", contains("курю", "кальян")));
    }

    @Test
    void exclusionWordsHideProfilesInAnyWordFormBeforePagination() throws Exception {
        String city = uniqueCity();
        String smoker = login(newPhone());
        publishWith(smoker, "Курильщик", city, List.of());
        putProfile(smoker, "{\"aboutText\":\"Иногда курю кальян по выходным\"}").andExpect(status().isOk());

        String hiker = login(newPhone());
        publishWith(hiker, "Турист", city, List.of());
        putProfile(hiker, "{\"aboutText\":\"Люблю горы и долгие прогулки\"}").andExpect(status().isOk());

        String withStatus = login(newPhone());
        publishWith(withStatus, "Статус", city, List.of());
        putProfile(withStatus, "{\"dailyStatusText\":\"Сегодня курит весь офис\"}").andExpect(status().isOk());

        String withExpiredStatus = login(newPhone());
        publishWith(withExpiredStatus, "Прошлое", city, List.of());
        putProfile(withExpiredStatus, "{\"dailyStatusText\":\"Курю на балконе\"}").andExpect(status().isOk());
        String expiredProfileId = json(get("/api/v1/me/profile"), withExpiredStatus, "$.id");
        jdbc.update("update profiles set daily_status_expires_at = now() - interval '1 hour' where id = ?",
                UUID.fromString(expiredProfileId));

        String viewer = login(newPhone());
        discovery(viewer, city, null, null, 20).andExpect(jsonPath("$.totalElements").value(4));

        putWords(viewer, "[\"курить\"]").andExpect(status().isOk());

        // "курить" hides "курю" (about) and "курит" (active status); an expired status no longer counts.
        discovery(viewer, city, null, null, 20)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("Турист", "Прошлое")));
        discovery(viewer, city, null, null, 1)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        // The filter is private to the viewer: others still see everyone.
        discovery(login(newPhone()), city, null, null, 20).andExpect(jsonPath("$.totalElements").value(4));

        putWords(viewer, "[]").andExpect(status().isOk());
        discovery(viewer, city, null, null, 20).andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void deletingAccountRemovesExclusionWords() throws Exception {
        String token = login(newPhone());
        String accountId = json(get("/api/v1/me"), token, "$.id");
        putWords(token, "[\"курю\"]").andExpect(status().isOk());

        mvc.perform(delete("/api/v1/me").header("Authorization", bearer(token))).andExpect(status().isNoContent());

        Integer left = jdbc.queryForObject("select count(*) from discovery_exclusion_words where account_id = ?",
                Integer.class, UUID.fromString(accountId));
        assertEquals(0, left);
    }

}
