package com.dating;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** City dictionary and the radius search (docs/matching.md, part 3). */
class CitiesGeoIntegrationTest extends IntegrationTestBase {

    @Test
    void citiesAreFoundByOfficialColloquialHistoricNamesAndWithTypos() throws Exception {
        int spb = cityId("Санкт-Петербург");
        mvc.perform(get("/api/v1/cities").queryParam("query", "питер"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(spb))
                .andExpect(jsonPath("$[0].name").value("Санкт-Петербург"))
                .andExpect(jsonPath("$[0].matchedName").value("Питер"));
        mvc.perform(get("/api/v1/cities").queryParam("query", "Ленинград")).andExpect(jsonPath("$[0].id").value(spb));
        mvc.perform(get("/api/v1/cities").queryParam("query", "СПб")).andExpect(jsonPath("$[0].id").value(spb));
        mvc.perform(get("/api/v1/cities").queryParam("query", "москва"))
                .andExpect(jsonPath("$[0].name").value("Москва"))
                .andExpect(jsonPath("$[0].matchedName").value(nullValue()));
        mvc.perform(get("/api/v1/cities").queryParam("query", "новосибирк")) // typo
                .andExpect(jsonPath("$[*].name", hasItem("Новосибирск")));
        mvc.perform(get("/api/v1/cities").queryParam("query", "екат"))      // prefix
                .andExpect(jsonPath("$[0].name").value("Екатеринбург"));
    }

    @Test
    void radiusKeepsNearbyPeopleAndLetsThroughThoseWhoLikedMe() throws Exception {
        int moscow = cityId("Москва");
        int khimki = cityId("Химки");
        int spb = cityId("Санкт-Петербург");
        String viewer = person("Виктор", "MALE", "FEMALE", moscow);
        put(viewer, "/api/v1/me/location", "{\"latitude\": 55.7512, \"longitude\": 37.6184}")
                .andExpect(jsonPath("$.locationShared").value(true));
        putProfile(viewer, "{\"searchRadiusKm\": 50}").andExpect(jsonPath("$.searchRadiusKm").value(50));

        String inMoscow = person("Москвичка", "FEMALE", "MALE", moscow);
        put(inMoscow, "/api/v1/me/location", "{\"latitude\": 55.70, \"longitude\": 37.50}");
        person("Из-Химок", "FEMALE", "MALE", khimki);
        person("Из-Питера", "FEMALE", "MALE", spb);
        String likesMe = person("Лайкнула-из-Питера", "FEMALE", "MALE", spb);

        // Everybody is a test account here, so restrict to the names of this test.
        mvc.perform(get("/api/v1/discovery/profiles").header("Authorization", bearer(viewer)).queryParam("limit", "50"))
                .andExpect(jsonPath("$.items[*].displayName", hasItem("Москвичка")))
                .andExpect(jsonPath("$.items[*].displayName", hasItem("Из-Химок")))
                .andExpect(jsonPath("$.items[*].displayName", not(hasItem("Из-Питера"))))
                .andExpect(jsonPath("$.items[*].displayName", not(hasItem("Лайкнула-из-Питера"))));

        String viewerProfile = json(get("/api/v1/me/profile"), viewer, "$.id");
        mvc.perform(post("/api/v1/discovery/profiles/" + viewerProfile + "/like").header("Authorization", bearer(likesMe)))
                .andExpect(status().isOk());
        String body = body(mvc.perform(get("/api/v1/discovery/profiles").header("Authorization", bearer(viewer))
                .queryParam("limit", "50")).andReturn());
        org.junit.jupiter.api.Assertions.assertTrue(
                JsonPath.<java.util.List<String>>read(body, "$.items[*].displayName").contains("Лайкнула-из-Питера"));
        // Distance only between two shared locations: «Москвичка» shares hers, «Из-Химок» uses the city centre.
        org.junit.jupiter.api.Assertions.assertEquals(9,
                ((Number) JsonPath.<java.util.List<Object>>read(body,
                        "$.items[?(@.displayName == 'Москвичка')].distanceKm").get(0)).intValue());
        org.junit.jupiter.api.Assertions.assertTrue(JsonPath.<java.util.List<Object>>read(body,
                "$.items[?(@.displayName == 'Из-Химок')].distanceKm").isEmpty());

        // A whole-city filter by id, and the old free-text filter mapped through the dictionary.
        mvc.perform(get("/api/v1/discovery/profiles").header("Authorization", bearer(viewer))
                        .queryParam("cityId", String.valueOf(spb)).queryParam("radiusKm", "20000"))
                .andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("Из-Питера", "Лайкнула-из-Питера")));
        mvc.perform(get("/api/v1/discovery/profiles").header("Authorization", bearer(viewer))
                        .queryParam("city", "Питер").queryParam("radiusKm", "20000"))
                .andExpect(jsonPath("$.items[*].displayName", containsInAnyOrder("Из-Питера", "Лайкнула-из-Питера")));

        mvc.perform(delete("/api/v1/me/location").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.locationShared").value(false));
        putProfile(viewer, "{\"cityId\": 999999999}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.cityId").exists());
        putProfile(viewer, "{\"city\": \"СПб\"}").andExpect(jsonPath("$.city").value("Санкт-Петербург"))
                .andExpect(jsonPath("$.cityId").value(spb));
    }

    // ------------------------------------------------------------------ helpers

    private int cityId(String query) throws Exception {
        return JsonPath.read(body(mvc.perform(get("/api/v1/cities").queryParam("query", query)).andReturn()), "$[0].id");
    }

    private String person(String name, String gender, String seeking, int cityId) throws Exception {
        String token = login(newPhone());
        putProfile(token, "{\"displayName\":\"" + name + "\",\"cityId\":" + cityId + ",\"gender\":\"" + gender
                + "\",\"seeking\":\"" + seeking + "\",\"birthDate\":\"" + LocalDate.now().minusYears(28) + "\"}")
                .andExpect(status().isOk());
        uploadPng(token);
        mvc.perform(post("/api/v1/me/profile/publish").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        return token;
    }

    private org.springframework.test.web.servlet.ResultActions put(String token, String path, String json)
            throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                        .header("Authorization", bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }
}
