package com.dating;

import com.dating.common.errors.ApiException;
import com.dating.economy.application.EconomyClock;
import com.dating.economy.application.EconomyService;
import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import com.dating.support.TestImages;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Catalog items, authors and images via the admin API; attribute validation; the public catalog;
 * time-limited items (which grant sources respect the window, owned items stay usable).
 */
class AdminCatalogIntegrationTest extends AdminTestSupport {

    @Autowired
    EconomyService economy;

    @Autowired
    EconomyClock clock;

    @Test
    void itemsAndAuthorsCrudWithAuditAndPublicCatalog() throws Exception {
        String editor = editorToken();
        String frame = "FRAME_QA_" + suffix();

        MvcResult authorResult = admin(post("/api/v1/admin/authors"),
                "{\"displayName\": \"Художница Аня\", \"contactNote\": \"telegram @anya\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revenueSharePercent").value(30)) // app.authors.default-revenue-share-percent
                .andExpect(jsonPath("$.itemCount").value(0))
                .andReturn();
        String authorId = JsonPath.read(body(authorResult), "$.id");
        admin(post("/api/v1/admin/authors"), "{\"displayName\": \"X\", \"revenueSharePercent\": 101}")
                .andExpect(status().isBadRequest());
        admin(post("/api/v1/admin/authors"), "{\"displayName\": \"X\", \"accountId\": \"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.accountId").exists());
        admin(put("/api/v1/admin/authors/" + authorId), """
                {"displayName": "Анна Иванова", "accountId": "%s", "revenueSharePercent": 40}""".formatted(accountId(editor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenueSharePercent").value(40))
                .andExpect(jsonPath("$.contactNote").value(nullValue()));
        as(editor, get("/api/v1/admin/authors"), null)
                .andExpect(jsonPath("$[?(@.id == '" + authorId + "')].displayName", contains("Анна Иванова")));

        as(editor, post("/api/v1/admin/catalog/items"), """
                {"code": "%s", "kind": "COSMETIC", "category": "PHOTO_FRAME", "title": "Рамка конкурса", "rarity": "EPIC",
                 "attributes": {"borderColor": "#112233", "borderWidth": 3, "glow": true}, "authorId": "%s"}"""
                .formatted(frame, authorId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipSlot").value("PHOTO_FRAME")) // derived from the category
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.authorName").value("Анна Иванова"));
        as(editor, post("/api/v1/admin/catalog/items"), """
                {"code": "%s", "kind": "COSMETIC", "category": "PHOTO_FRAME", "title": "Дубль",
                 "attributes": {"borderColor": "#112233", "borderWidth": 3}}""".formatted(frame))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_EXISTS"));

        // Public catalog: active items with author, filters by kind/category.
        as(login(newPhone()), get("/api/v1/catalog").queryParam("kind", "COSMETIC").queryParam("category", "PHOTO_FRAME"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItems("FRAME_STUB_GOLD", frame)))
                .andExpect(jsonPath("$[*].category", everyItem(is("PHOTO_FRAME"))))
                .andExpect(jsonPath("$[?(@.code == '" + frame + "')].author.displayName", contains("Анна Иванова")))
                .andExpect(jsonPath("$[?(@.code == '" + frame + "')].availableNow", contains(true)))
                .andExpect(jsonPath("$[?(@.code == 'FRAME_STUB_GOLD')].author", contains(nullValue())));
        as(editor, get("/api/v1/catalog").queryParam("kind", "CONSUMABLE"), null)
                .andExpect(jsonPath("$[*].code", hasItems("SUPERLIKE", "GACHA_TICKET")))
                .andExpect(jsonPath("$[*].kind", everyItem(is("CONSUMABLE"))));
        as(editor, get("/api/v1/catalog").queryParam("kind", "HAT"), null).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/catalog")).andExpect(status().isUnauthorized());

        // Full replace of editable fields; hiding removes it from the public catalog.
        as(editor, put("/api/v1/admin/catalog/items/" + frame), """
                {"title": "Рамка победителя", "rarity": "LEGENDARY", "active": false, "authorId": "%s",
                 "attributes": {"borderColor": "#445566", "borderWidth": 5}}""".formatted(authorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Рамка победителя"))
                .andExpect(jsonPath("$.attributes.glow").doesNotExist());
        as(editor, get("/api/v1/catalog"), null).andExpect(jsonPath("$[*].code", not(hasItem(frame))));
        as(editor, get("/api/v1/admin/catalog/items/" + frame), null).andExpect(jsonPath("$.active").value(false));
        as(editor, put("/api/v1/admin/catalog/items/NO_SUCH_ITEM"), """
                {"title": "x", "active": true, "attributes": {}}""").andExpect(status().isNotFound());

        admin(delete("/api/v1/admin/authors/" + authorId), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTHOR_IN_USE"));
        MvcResult lonely = admin(post("/api/v1/admin/authors"), "{\"displayName\": \"Без работ\"}").andReturn();
        admin(delete("/api/v1/admin/authors/" + JsonPath.read(body(lonely), "$.id")), null).andExpect(status().isNoContent());

        admin(get("/api/v1/admin/audit").queryParam("entityType", "CATALOG_ITEM").queryParam("entityId", frame), null)
                .andExpect(jsonPath("$[*].action", contains("UPDATE", "CREATE")))
                .andExpect(jsonPath("$[0].before.title").value("Рамка конкурса"))
                .andExpect(jsonPath("$[0].after.title").value("Рамка победителя"));
        admin(get("/api/v1/admin/audit").queryParam("entityType", "AUTHOR").queryParam("entityId", authorId), null)
                .andExpect(jsonPath("$[*].action", contains("UPDATE", "CREATE")));
    }

    @Test
    void attributesAreValidatedPerCategory() throws Exception {
        String editor = editorToken();
        String item = """
                {"code": "%s", "kind": "%s", "category": "%s", "title": "Проверка", "attributes": %s}""";

        as(editor, post("/api/v1/admin/catalog/items"), item.formatted("FRAME_QA_" + suffix(), "COSMETIC", "PHOTO_FRAME",
                "{\"borderColor\": \"red\", \"borderWidth\": 3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ATTRIBUTES"))
                .andExpect(jsonPath("$.fieldErrors['attributes.borderColor']").exists());
        as(editor, post("/api/v1/admin/catalog/items"), item.formatted("STATUS_QA_" + suffix(), "COSMETIC", "PROFILE_STATUS",
                "{\"emoji\": \"🔥\", \"color\": \"#FF0000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['attributes.label']").exists());
        as(editor, post("/api/v1/admin/catalog/items"), item.formatted("WALL_QA_" + suffix(), "COSMETIC", "CHAT_WALLPAPER",
                "{\"background\": \"#000000\", \"accent\": \"#FFFFFF\", \"pattern\": \"Big Stars!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['attributes.pattern']").exists());
        as(editor, post("/api/v1/admin/catalog/items"), item.formatted("STICKERS_QA_" + suffix(), "COSMETIC", "STICKER_PACK",
                "{\"emoji\": \"🐶\", \"stickers\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['attributes.stickers']").exists());
        as(editor, post("/api/v1/admin/catalog/items"), item.formatted("TOKEN_QA_" + suffix(), "CONSUMABLE", "TOKEN", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['attributes.icon']").exists());
        as(editor, post("/api/v1/admin/catalog/items"), item.formatted("FRAME_QA_" + suffix(), "COSMETIC", "PHOTO_FRAME",
                "{\"borderColor\": \"#000000\", \"borderWidth\": 2, \"imageUrl\": \"https://evil.example/x.png\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['attributes.imageUrl']").exists());

        // Slot rules: frames go to PHOTO_FRAME only, consumables and sticker packs are not worn.
        as(editor, post("/api/v1/admin/catalog/items"), """
                {"code": "FRAME_QA_%s", "kind": "COSMETIC", "category": "PHOTO_FRAME", "equipSlot": "PROFILE_STATUS",
                 "title": "x", "attributes": {"borderColor": "#000000", "borderWidth": 2}}""".formatted(suffix()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.equipSlot").exists());
        as(editor, post("/api/v1/admin/catalog/items"), """
                {"code": "TOKEN_QA_%s", "kind": "CONSUMABLE", "category": "TOKEN", "equipSlot": "PHOTO_FRAME",
                 "title": "x", "attributes": {"icon": "coin"}}""".formatted(suffix()))
                .andExpect(status().isBadRequest());
        as(editor, post("/api/v1/admin/catalog/items"), """
                {"code": "FRAME_QA_%s", "kind": "COSMETIC", "category": "PHOTO_FRAME", "title": "x", "rarity": "MYTHIC",
                 "attributes": {"borderColor": "#000000", "borderWidth": 2}}""".formatted(suffix()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.rarity").exists());

        // Valid ones, including a new category (any object) worn in an existing slot, and extra keys.
        as(editor, post("/api/v1/admin/catalog/items"), item.formatted("STICKERS_QA_" + suffix(), "COSMETIC", "STICKER_PACK",
                "{\"emoji\": \"🐶\", \"stickers\": [\"dog_wave\", \"dog_sleep\"], \"animated\": true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipSlot").value(nullValue()));
        as(editor, post("/api/v1/admin/catalog/items"), """
                {"code": "ANIMFRAME_QA_%s", "kind": "COSMETIC", "category": "ANIMATED_FRAME", "equipSlot": "PHOTO_FRAME",
                 "title": "Анимированная", "attributes": {"lottie": "sparkles", "speed": 2}}""".formatted(suffix()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipSlot").value("PHOTO_FRAME"));
        as(editor, post("/api/v1/admin/catalog/items"), item.formatted("TOKEN_QA_" + suffix(), "CONSUMABLE", "TOKEN",
                "{\"icon\": \"coin\"}"))
                .andExpect(status().isCreated());
        // Updates are validated against the item's own category.
        String statusItem = "STATUS_QA_" + suffix();
        as(editor, post("/api/v1/admin/catalog/items"), item.formatted(statusItem, "COSMETIC", "PROFILE_STATUS",
                "{\"emoji\": \"🔥\", \"label\": \"Огонь\", \"color\": \"#FF0000\"}"))
                .andExpect(status().isCreated());
        as(editor, put("/api/v1/admin/catalog/items/" + statusItem), """
                {"title": "Огонь", "active": true, "attributes": {"emoji": "🔥"}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ATTRIBUTES"));
    }

    @Test
    void itemImagesAreStrippedStoredAndServedPublicly() throws Exception {
        String editor = editorToken();
        String frame = "FRAME_QA_" + suffix();
        as(editor, post("/api/v1/admin/catalog/items"), """
                {"code": "%s", "kind": "COSMETIC", "category": "PHOTO_FRAME", "title": "С картинкой",
                 "attributes": {"borderColor": "#112233", "borderWidth": 3}}""".formatted(frame))
                .andExpect(status().isCreated());

        MvcResult uploaded = mvc.perform(multipart("/api/v1/admin/catalog/items/" + frame + "/image")
                        .file(new MockMultipartFile("file", "frame.png", "image/png", TestImages.png(4, 4)))
                        .header("Authorization", bearer(editor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.imageUrl").value(startsWith("/api/v1/catalog/images/")))
                .andExpect(jsonPath("$.attributes.borderColor").value("#112233"))
                .andReturn();
        String imageUrl = JsonPath.read(body(uploaded), "$.attributes.imageUrl");

        mvc.perform(get(imageUrl)) // no Authorization: cosmetics are public
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", containsString("public")));
        mvc.perform(get("/api/v1/catalog/images/" + UUID.randomUUID())).andExpect(status().isNotFound());

        // The URL survives a full update that sends it back.
        as(editor, put("/api/v1/admin/catalog/items/" + frame), """
                {"title": "С картинкой", "active": true, "attributes": {"borderColor": "#112233", "borderWidth": 3, "imageUrl": "%s"}}"""
                .formatted(imageUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.imageUrl").value(imageUrl));

        mvc.perform(multipart("/api/v1/admin/catalog/items/" + frame + "/image")
                        .file(new MockMultipartFile("file", "x.png", "image/png", "not an image at all".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(editor)))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/v1/admin/catalog/items/" + frame + "/image")
                        .file(new MockMultipartFile("file", "frame.png", "image/png", TestImages.png(4, 4)))
                        .header("Authorization", bearer(login(newPhone()))))
                .andExpect(status().isForbidden());

        admin(get("/api/v1/admin/audit").queryParam("entityType", "CATALOG_ITEM").queryParam("entityId", frame), null)
                .andExpect(jsonPath("$[*].action", contains("UPDATE", "IMAGE_UPLOAD", "CREATE")));
    }

    @Test
    void timeLimitedItemsAreGrantedOnlyByOfferSourcesInsideTheWindow() throws Exception {
        String editor = editorToken();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String expired = createFrame(editor, null, now.minus(Duration.ofHours(1)));
        String upcoming = createFrame(editor, now.plus(Duration.ofDays(1)), null);
        String current = createFrame(editor, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(2)));

        as(editor, post("/api/v1/admin/catalog/items"), """
                {"code": "FRAME_QA_%s", "kind": "COSMETIC", "category": "PHOTO_FRAME", "title": "x",
                 "attributes": {"borderColor": "#000000", "borderWidth": 2},
                 "availableFrom": "%s", "availableUntil": "%s"}""".formatted(suffix(), now, now))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.availableUntil").exists());

        String token = login(newPhone());
        UUID account = UUID.fromString(accountId(token));

        // Offer-like sources respect the window.
        ApiException error = assertThrows(ApiException.class, () -> economy.grant(account, RewardSource.PURCHASE,
                "order", "purchase:" + UUID.randomUUID(), List.of(new Reward.Item(expired, 1))));
        assertEquals("ITEM_NOT_AVAILABLE", error.getCode());
        assertEquals(expired, error.getDetails().get("itemCode"));
        assertEquals(now.minus(Duration.ofHours(1)).toString(), error.getDetails().get("availableUntil"));
        assertThrows(ApiException.class, () -> economy.grant(account, RewardSource.PROMO, "promo",
                "promo:" + UUID.randomUUID(), List.of(new Reward.Item(upcoming, 1))));
        assertTrue(economy.grant(account, RewardSource.PURCHASE, "order", "purchase:" + UUID.randomUUID(),
                List.of(new Reward.Item(current, 1))));

        // Manual, earned and gacha grants ignore it (gacha checks its own banner window).
        for (RewardSource source : List.of(RewardSource.ADMIN, RewardSource.TESTING, RewardSource.ACHIEVEMENT, RewardSource.GACHA)) {
            assertTrue(economy.grant(account, source, "x", source + ":" + UUID.randomUUID(),
                    List.of(new Reward.Item(source == RewardSource.GACHA ? upcoming : expired, 1))), source.name());
        }

        // Owned items stay owned and equippable outside the window.
        as(token, put("/api/v1/me/cosmetics/equipped/PHOTO_FRAME"), "{\"itemCode\": \"" + expired + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipped.PHOTO_FRAME").value(expired));

        as(token, get("/api/v1/catalog").queryParam("category", "PHOTO_FRAME"), null)
                .andExpect(jsonPath("$[?(@.code == '" + expired + "')].availableNow", contains(false)))
                .andExpect(jsonPath("$[?(@.code == '" + upcoming + "')].availableNow", contains(false)))
                .andExpect(jsonPath("$[?(@.code == '" + current + "')].availableNow", contains(true)))
                .andExpect(jsonPath("$[?(@.code == '" + current + "')].availableUntil",
                        contains(now.plus(Duration.ofDays(2)).toString())));

        // The window is checked in the account's economy time (test accounts can move it).
        UUID other = UUID.fromString(accountId(login(newPhone())));
        clock.advance(other, Duration.ofDays(3));
        assertThrows(ApiException.class, () -> economy.grant(other, RewardSource.PURCHASE, "order",
                "purchase:" + UUID.randomUUID(), List.of(new Reward.Item(current, 1))));
        assertTrue(economy.grant(other, RewardSource.PURCHASE, "order", "purchase:" + UUID.randomUUID(),
                List.of(new Reward.Item(upcoming, 1))));
    }

    private String createFrame(String editor, Instant from, Instant until) throws Exception {
        String code = "FRAME_QA_" + suffix();
        as(editor, post("/api/v1/admin/catalog/items"), """
                {"code": "%s", "kind": "COSMETIC", "category": "PHOTO_FRAME", "title": "Лимитированная",
                 "attributes": {"borderColor": "#000000", "borderWidth": 2}, "availableFrom": %s, "availableUntil": %s}"""
                .formatted(code, quoted(from), quoted(until)))
                .andExpect(status().isCreated());
        return code;
    }

    private static String quoted(Instant instant) {
        return instant == null ? "null" : "\"" + instant + "\"";
    }
}
