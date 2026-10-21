package com.dating;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Interests (closed dictionary), favourite artists, the zodiac switch and the profile change history. */
class InterestsMusicIntegrationTest extends IntegrationTestBase {

    @Test
    void interestsDictionaryIsGroupedAndPublic() throws Exception {
        mvc.perform(get("/api/v1/interests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(10))))
                .andExpect(jsonPath("$[0].code").value("SPORT"))
                .andExpect(jsonPath("$[0].label").value("Спорт"))
                .andExpect(jsonPath("$[0].items[0].code").value("RUNNING"))
                .andExpect(jsonPath("$[0].items[0].categoryLabel").value("Спорт"));
    }

    @Test
    void interestsAndArtistsAreSavedValidatedAndShown() throws Exception {
        String city = uniqueCity();
        String me = login(newPhone());
        publishWith(me, "Мария", city, List.of());

        putProfile(me, "{\"interestCodes\": [\"RUNNING\", \"NOPE\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.interestCodes").exists());
        putProfile(me, "{\"interestCodes\": [\"RUNNING\",\"GYM\",\"YOGA\",\"PILATES\",\"SWIMMING\",\"CYCLING\","
                + "\"FOOTBALL\",\"TENNIS\",\"COFFEE\",\"TEA\",\"WINE\"]}").andExpect(status().isBadRequest());
        putProfile(me, "{\"interestCodes\": [\"COFFEE\", \"RUNNING\", \"MOVIES\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.interests[*].code", contains("RUNNING", "COFFEE", "MOVIES")));

        mvc.perform(get("/api/v1/artists").queryParam("q", "земф")).andExpect(status().isUnauthorized());
        String found = body(mvc.perform(get("/api/v1/artists").queryParam("q", "земф")
                .header("Authorization", bearer(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Земфира")).andReturn());
        Integer zemfira = com.jayway.jsonpath.JsonPath.read(found, "$[0].id");
        mvc.perform(get("/api/v1/artists").queryParam("q", "колдплей").header("Authorization", bearer(me)))
                .andExpect(status().isOk()); // no match locally, MusicBrainz is off in tests
        putProfile(me, "{\"artistIds\": [" + zemfira + ", 999999]}").andExpect(status().isBadRequest());
        putProfile(me, "{\"artistIds\": [" + zemfira + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.artists[0].name").value("Земфира"));

        String viewer = login(newPhone());
        canSearch(viewer);
        putProfile(viewer, "{\"interestCodes\": [\"RUNNING\", \"TENNIS\"], \"artistIds\": [" + zemfira + "]}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/discovery/profiles").queryParam("city", city).header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.items[0].common", contains("Бег", "Земфира")))
                .andExpect(jsonPath("$.items[0].interests[*].label", contains("Бег", "Кофе", "Кино")))
                .andExpect(jsonPath("$.items[0].artists[0].name").value("Земфира"));
    }

    @Test
    void zodiacIsShownOnlyWhenTheOwnerWants() throws Exception {
        String city = uniqueCity();
        String me = login(newPhone());
        publishWith(me, "Лев", city, List.of());
        // The age is locked after publishing; set the date directly.
        jdbc.update("UPDATE profiles SET birth_date = DATE '1990-08-10' WHERE id = ?::uuid",
                json(get("/api/v1/me/profile"), me, "$.id"));
        String viewer = login(newPhone());
        canSearch(viewer);

        mvc.perform(get("/api/v1/me/profile").header("Authorization", bearer(me)))
                .andExpect(jsonPath("$.zodiac").value("Лев"))
                .andExpect(jsonPath("$.showZodiac").value(false));
        mvc.perform(get("/api/v1/discovery/profiles").queryParam("city", city).header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.items[0].zodiac").doesNotExist());

        putProfile(me, "{\"showZodiac\": true}").andExpect(jsonPath("$.showZodiac").value(true));
        mvc.perform(get("/api/v1/discovery/profiles").queryParam("city", city).header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.items[0].zodiac").value("Лев"));
    }

    @Test
    void previousStatesGoToTheHistory() throws Exception {
        String me = login(newPhone());
        putProfile(me, "{\"displayName\": \"Ольга\"}").andExpect(status().isOk());
        putProfile(me, "{\"displayName\": \"Оля\", \"aboutText\": \"Новый текст\"}").andExpect(status().isOk());
        putProfile(me, "{\"displayName\": \"Оля\"}").andExpect(status().isOk()); // no change, no entry
        UUID profileId = UUID.fromString(json(get("/api/v1/me/profile"), me, "$.id"));
        Integer entries = jdbc.queryForObject("SELECT count(*) FROM profile_history WHERE profile_id = ?",
                Integer.class, profileId);
        assertTrue(entries == 2, "entries: " + entries);
        String last = jdbc.queryForObject(
                "SELECT snapshot->>'displayName' FROM profile_history WHERE profile_id = ? ORDER BY id DESC LIMIT 1",
                String.class, profileId);
        assertTrue("Ольга".equals(last), last);
    }
}
