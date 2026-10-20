package com.dating;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Two-way hard filters (docs/matching.md, part 1): gender, age ranges and goals must fit both ways. */
class MatchingFiltersIntegrationTest extends IntegrationTestBase {

    @Test
    void genderAgeAndGoalsMustFitBothWays() throws Exception {
        String city = uniqueCity();
        // Антон: a 30-year-old man looking for women, serious relationship.
        String a = person(city, "Антон", "MALE", "FEMALE", 30, "\"goalCodes\":[\"SERIOUS_RELATIONSHIP\"]");
        person(city, "Б-мужчина", "MALE", "FEMALE", 28, "");                  // not a woman
        person(city, "В-ищет-женщин", "FEMALE", "FEMALE", 27, "");            // does not look for men
        person(city, "Г-подходит", "FEMALE", "MALE", 27, "\"goalCodes\":[\"FAMILY\",\"SERIOUS_RELATIONSHIP\"]");
        person(city, "Д-до-28", "FEMALE", "MALE", 26, "\"ageTo\":28");         // A is too old for her
        person(city, "Е-только-дружба", "FEMALE", "MALE", 29, "\"goalCodes\":[\"FRIENDSHIP\"]");
        person(city, "Ж-не-знает", "FEMALE", "MALE", 31, "\"goalCodes\":[\"NOT_SURE\"]");
        person(city, "З-без-целей", "FEMALE", "MALE", 33, "");
        person(city, "И-слишком-старше", "FEMALE", "MALE", 45, "");            // outside A's range

        putProfile(a, "{\"ageFrom\":20,\"ageTo\":40}").andExpect(status().isOk());

        discovery(a, city, null, null, 50)
                .andExpect(jsonPath("$.items[*].displayName",
                        containsInAnyOrder("Г-подходит", "Ж-не-знает", "З-без-целей")));
        // The woman looking for women does not see A either.
        String v = loginAs(city, "В-ищет-женщин-2", "FEMALE", "FEMALE", 27);
        discovery(v, city, null, null, 50).andExpect(jsonPath("$.items[*].displayName",
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Антон"))));
    }

    @Test
    void feedNeedsOwnGenderAndBirthDateAndGenderLocksAfterPublishing() throws Exception {
        String token = login(newPhone());
        mvc.perform(get("/api/v1/discovery/profiles").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"))
                .andExpect(jsonPath("$.fieldErrors.gender").exists())
                .andExpect(jsonPath("$.fieldErrors.birthDate").exists());

        String born = LocalDate.now().minusYears(25).toString();
        putProfile(token, "{\"gender\":\"FEMALE\",\"seeking\":\"MALE\",\"birthDate\":\"" + born + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.age").value(25))
                .andExpect(jsonPath("$.ageFrom").value(18))
                .andExpect(jsonPath("$.ageTo").value(org.hamcrest.Matchers.nullValue()));
        // Still a draft: a typo can be fixed.
        putProfile(token, "{\"gender\":\"MALE\"}").andExpect(status().isOk());
        putProfile(token, "{\"gender\":\"FEMALE\",\"displayName\":\"Ира\",\"city\":\"" + uniqueCity() + "\"}")
                .andExpect(status().isOk());
        uploadPng(token);
        mvc.perform(post("/api/v1/me/profile/publish").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        putProfile(token, "{\"gender\":\"MALE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.gender").exists());
        putProfile(token, "{\"seeking\":\"FEMALE\",\"ageFrom\":25,\"ageTo\":35}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeking").value("FEMALE"))
                .andExpect(jsonPath("$.ageTo").value(35));
        putProfile(token, "{\"clearAgeTo\":true}").andExpect(jsonPath("$.ageTo").value(org.hamcrest.Matchers.nullValue()));
        putProfile(token, "{\"ageFrom\":30,\"ageTo\":20}").andExpect(status().isBadRequest());
        putProfile(token, "{\"birthDate\":\"" + LocalDate.now().minusYears(17) + "\"}").andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private String person(String city, String name, String gender, String seeking, int age, String extra)
            throws Exception {
        String token = loginAs(city, name, gender, seeking, age);
        if (!extra.isEmpty()) {
            putProfile(token, "{" + extra + "}").andExpect(status().isOk());
        }
        uploadPng(token);
        mvc.perform(post("/api/v1/me/profile/publish").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        return token;
    }

    private String loginAs(String city, String name, String gender, String seeking, int age) throws Exception {
        String token = login(newPhone());
        String born = LocalDate.now().minusYears(age).minusDays(10).toString();
        putProfile(token, "{\"displayName\":\"" + name + "\",\"city\":\"" + city + "\",\"gender\":\"" + gender
                + "\",\"seeking\":\"" + seeking + "\",\"birthDate\":\"" + born + "\"}")
                .andExpect(status().isOk());
        return token;
    }
}
