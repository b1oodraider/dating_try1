package com.dating;

import com.dating.support.TestImages;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test accounts and the testing API. The test profile allowlists +7 800 000-00-0X with OTP 654321
 * and gives premium a weekly TOP_UP of 5 TEST_SUPERLIKE and a weekly ADD of 1 TEST_TICKET.
 */
class TestingApiIntegrationTest extends IntegrationTestBase {

    private static final String TEST_OTP = "654321";

    @Test
    void realAccountsCannotUseTestingApi() throws Exception {
        String token = login(newPhone());
        mvc.perform(get("/api/v1/testing/state").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEST_ACCOUNT_REQUIRED"));
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.testAccount").value(false));
    }

    @Test
    void testPhonesLogInWithFixedCodeAndAreFlagged() throws Exception {
        String token = testLogin("+7 800 000-00-01");
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.testAccount").value(true));
    }

    @Test
    void testerControlsPremiumConsumablesAndEconomyClock() throws Exception {
        String token = testLogin("+78000000002");

        testing(token, post("/api/v1/testing/premium"), "{\"days\": 7}")
                .andExpect(jsonPath("$.wallet.premium.active").value(true))
                .andExpect(jsonPath(item("TEST_SUPERLIKE", "periodicQuantity"), contains(5)))
                .andExpect(jsonPath(item("TEST_TICKET", "quantity"), contains(1)));

        testing(token, post("/api/v1/testing/wallet/items/GACHA_TICKET"), "{\"quantity\": 3}")
                .andExpect(jsonPath(item("GACHA_TICKET", "quantity"), contains(3)));
        testing(token, put("/api/v1/testing/wallet/items/GACHA_TICKET"), "{\"quantity\": 10}")
                .andExpect(jsonPath(item("GACHA_TICKET", "quantity"), contains(10)));

        // "Next week": the 7-day premium has run out, so no new allowance.
        testing(token, post("/api/v1/testing/clock/advance"), "{\"days\": 7}")
                .andExpect(jsonPath("$.clockOffsetDays").value(7.0))
                .andExpect(jsonPath("$.wallet.premium.active").value(false))
                .andExpect(jsonPath(item("TEST_TICKET", "quantity"), contains(1)));

        // Premium again in the new week: ADD allowance accumulates, TOP_UP stays at 5.
        testing(token, post("/api/v1/testing/premium"), "{\"days\": 30}")
                .andExpect(jsonPath("$.wallet.premium.active").value(true))
                .andExpect(jsonPath(item("TEST_TICKET", "quantity"), contains(2)))
                .andExpect(jsonPath(item("TEST_SUPERLIKE", "quantity"), contains(5)));

        testing(token, delete("/api/v1/testing/premium"), null)
                .andExpect(jsonPath("$.wallet.premium.active").value(false));
        testing(token, delete("/api/v1/testing/clock"), null)
                .andExpect(jsonPath("$.clockOffsetDays").value(0.0));

        testing(token, post("/api/v1/testing/wallet/items/NO_SUCH_ITEM"), "{\"quantity\": 1}", 404);
    }

    @Test
    void achievementsGrantRewardsOnceAndCanBeReset() throws Exception {
        String token = testLogin("+78000000003");
        String collector = "/api/v1/testing/achievements/STUB_COLLECTOR";

        testing(token, post(collector + "/complete"), null)
                .andExpect(jsonPath("$.achievements[?(@.code == 'STUB_COLLECTOR')].completed", contains(true)))
                .andExpect(jsonPath(item("GACHA_TICKET", "quantity"), contains(2)))
                .andExpect(jsonPath(item("STATUS_STUB_EARLY_BIRD", "quantity"), contains(1)))
                .andExpect(jsonPath(item("STICKERS_STUB_CATS", "category"), contains("STICKER_PACK")));

        testing(token, post(collector + "/complete"), null)
                .andExpect(jsonPath(item("GACHA_TICKET", "quantity"), contains(2)));

        testing(token, delete(collector), null)
                .andExpect(jsonPath("$.achievements[?(@.code == 'STUB_COLLECTOR')].completed", contains(false)));
        testing(token, post(collector + "/complete"), null)
                .andExpect(jsonPath(item("GACHA_TICKET", "quantity"), contains(4)));

        testing(token, post("/api/v1/testing/achievements/NOPE/complete"), null, 404);
    }

    @Test
    void equippedCosmeticsAreShownToTestAccountsOnly() throws Exception {
        String city = uniqueCity();
        String owner = testLogin("+78000000004");
        publishWith(owner, "Тестер", city, List.of());
        testing(owner, post("/api/v1/testing/achievements/STUB_PROFILE_PUBLISHED/complete"), null); // gold frame
        testing(owner, post("/api/v1/testing/achievements/STUB_COLLECTOR/complete"), null);         // status, wallpaper

        equip(owner, "PHOTO_FRAME", "FRAME_STUB_GOLD").andExpect(status().isOk())
                .andExpect(jsonPath("$.equipped.PHOTO_FRAME").value("FRAME_STUB_GOLD"));
        equip(owner, "PROFILE_STATUS", "STATUS_STUB_EARLY_BIRD").andExpect(status().isOk());
        equip(owner, "CHAT_WALLPAPER", "WALLPAPER_STUB_NIGHT").andExpect(status().isOk());

        equip(owner, "PHOTO_FRAME", "FRAME_STUB_NEON").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_OWNED"));
        equip(owner, "PHOTO_FRAME", "STATUS_STUB_EARLY_BIRD").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_SLOT"));
        equip(owner, "HAT", "FRAME_STUB_GOLD").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SLOT"));

        mvc.perform(get("/api/v1/me/profile").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.decorations.PHOTO_FRAME.attributes.borderColor").value("#D4AF37"));

        String testViewer = testLogin("+78000000005");
        discovery(testViewer, city, null, null, 20)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].decorations.PHOTO_FRAME.code").value("FRAME_STUB_GOLD"))
                .andExpect(jsonPath("$.items[0].decorations.PROFILE_STATUS.attributes.emoji").value("🐦"))
                .andExpect(jsonPath("$.items[0].decorations.CHAT_WALLPAPER").doesNotExist()); // private slot

        // Real users never see test accounts (and vice versa).
        discovery(login(newPhone()), city, null, null, 20).andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(delete("/api/v1/me/cosmetics/equipped/PHOTO_FRAME").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.equipped.PHOTO_FRAME").doesNotExist());
    }

    @Test
    void seedProfilesAreCreatedWithPhotosPremiumAndCosmeticsAndCanBeDeleted() throws Exception {
        String city = uniqueCity();
        String tester = testLogin("+78000000006");
        testing(login(newPhone()), get("/api/v1/testing/profiles"), null, 403)
                .andExpect(jsonPath("$.code").value("TEST_ACCOUNT_REQUIRED"));

        String seedJson = "{\"profile\": {\"gender\": \"MALE\", \"seeking\": \"MALE\", \"displayName\": \"Сид\", \"age\": 25, \"city\": \"" + city + "\","
                + " \"aboutText\": \"Люблю горы\", \"heightCm\": 170, \"goalCodes\": [\"DATING\"]},"
                + " \"premiumDays\": 30, \"cosmetics\": [\"FRAME_STUB_GOLD\", \"STATUS_STUB_BOOKWORM\", \"STICKERS_STUB_CATS\"]}";
        MvcResult created = testing(tester, post("/api/v1/testing/profiles"), seedJson, 201)
                .andExpect(jsonPath("$.profile.publicationStatus").value("DRAFT"))
                .andExpect(jsonPath("$.profile.heightCm").value(170))
                .andExpect(jsonPath("$.profile.premium").value(true))
                .andExpect(jsonPath("$.profile.decorations.PHOTO_FRAME.code").value("FRAME_STUB_GOLD"))
                .andExpect(jsonPath("$.profile.decorations.PROFILE_STATUS.code").value("STATUS_STUB_BOOKWORM"))
                .andReturn();
        String seedId = JsonPath.read(body(created), "$.accountId");
        String seedPath = "/api/v1/testing/profiles/" + seedId;

        testing(tester, post(seedPath + "/publish"), null, 400)
                .andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"));
        mvc.perform(multipart(seedPath + "/photos")
                        .file(new MockMultipartFile("file", "p.png", "image/png", TestImages.png(3, 3)))
                        .header("Authorization", bearer(tester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPrimary").value(true));
        testing(tester, post(seedPath + "/publish"), null)
                .andExpect(jsonPath("$.profile.publicationStatus").value("PUBLISHED"));
        testing(tester, put(seedPath), "{\"aboutText\": \"Люблю море\"}")
                .andExpect(jsonPath("$.profile.aboutText").value("Люблю море"));

        discovery(tester, city, null, null, 20)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("Сид"))
                .andExpect(jsonPath("$.items[0].premium").value(true));
        discovery(login(newPhone()), city, null, null, 20).andExpect(jsonPath("$.totalElements").value(0));
        testing(tester, get("/api/v1/testing/profiles"), null)
                .andExpect(jsonPath("$[*].accountId", hasItem(seedId)));

        testing(tester, post("/api/v1/testing/profiles"), "{\"profile\": {\"age\": 10}}", 400);
        testing(tester, post("/api/v1/testing/profiles"), "{\"profile\": {}, \"cosmetics\": [\"NOPE\"]}", 404);
        // Only seed accounts can be managed here, not the tester's own (or any real) account.
        String testerId = json(get("/api/v1/me"), tester, "$.id");
        testing(tester, delete("/api/v1/testing/profiles/" + testerId), null, 404)
                .andExpect(jsonPath("$.code").value("SEED_PROFILE_NOT_FOUND"));

        testing(tester, delete(seedPath), null, 204);
        testing(tester, delete(seedPath), null, 404);
        discovery(tester, city, null, null, 20).andExpect(jsonPath("$.totalElements").value(0));

        testing(tester, post("/api/v1/testing/profiles"), "{\"profile\": {\"gender\": \"MALE\", \"seeking\": \"MALE\", \"displayName\": \"Ещё\"}}", 201);
        testing(tester, delete("/api/v1/testing/profiles"), null)
                .andExpect(jsonPath("$.deleted", greaterThanOrEqualTo(1)));
        testing(tester, get("/api/v1/testing/profiles"), null).andExpect(jsonPath("$", hasSize(0)));
    }

    // ------------------------------------------------------------------ helpers

    private String testLogin(String phone) throws Exception {
        MvcResult challenge = mvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = JsonPath.read(body(challenge), "$.challengeId");
        MvcResult session = mvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + TEST_OTP + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(body(session), "$.token");
    }

    private ResultActions testing(String token,
                                  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  String json) throws Exception {
        return testing(token, request, json, 200);
    }

    private ResultActions testing(String token,
                                  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  String json, int expectedStatus) throws Exception {
        request.header("Authorization", bearer(token));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus));
    }

    private ResultActions equip(String token, String slot, String itemCode) throws Exception {
        return mvc.perform(put("/api/v1/me/cosmetics/equipped/" + slot)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemCode\":\"" + itemCode + "\"}"));
    }

    private static String item(String code, String field) {
        return "$.wallet.items[?(@.code == '" + code + "')]." + field;
    }
}
