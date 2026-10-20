package com.dating.profiles;

import com.dating.profiles.application.ProfileService;
import com.dating.profiles.application.ProfileTips;
import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProfileRulesTest {

    @Test
    void newProfileIsEmptyAndCannotBePublished() {
        Profile profile = new Profile(UUID.randomUUID(), UUID.randomUUID());

        assertNull(profile.getDisplayName());
        assertNull(profile.getAge());
        assertEquals(0, ProfileTips.percent(ProfileTips.of(profile, 0)));
        assertEquals(List.of("displayName", "birthDate", "gender", "seeking", "city", "photos"),
                List.copyOf(ProfileService.missingForPublish(profile, 0).keySet()));
    }

    @Test
    void blankNameDoesNotCountAsFilled() {
        Profile profile = new Profile(UUID.randomUUID(), UUID.randomUUID());
        profile.setDisplayName("   ");
        assertTrue(ProfileService.missingForPublish(profile, 1).containsKey("displayName"));
    }

    @Test
    void fullProfileIsComplete() {
        Profile profile = new Profile(UUID.randomUUID(), UUID.randomUUID());
        profile.setDisplayName("Анна");
        profile.setBirthDate(java.time.LocalDate.now().minusYears(26));
        profile.setGender(com.dating.profiles.domain.Gender.FEMALE);
        profile.setSeeking(com.dating.profiles.domain.Gender.MALE);
        profile.setCity("Москва");
        profile.setAboutText("Люблю долгие прогулки, плёночную фотографию и хороший кофе");
        profile.replaceTags(Set.of(tag("A"), tag("B"), tag("C")));

        assertTrue(ProfileService.missingForPublish(profile, 1).isEmpty());
        // name, age, city, a photo, a long enough about text; cards from one category only, no goals/height/selfie
        List<ProfileTips.Tip> tips = ProfileTips.of(profile, 1);
        assertEquals(60, ProfileTips.percent(tips));
        assertEquals(List.of("MORE_PHOTOS", "CARDS", "GOALS", "VERIFIED", "HEIGHT"),
                tips.stream().filter(t -> !t.done()).map(ProfileTips.Tip::code).toList(), "most valuable first");
        assertEquals(100, ProfileTips.of(profile, 0).stream().mapToInt(ProfileTips.Tip::weight).sum());
        assertEquals(45, ProfileTips.percent(ProfileTips.of(profile, 0)), "the first photo is worth 15");
    }

    @Test
    void dailyStatusDisappearsAfterExpiry() {
        Profile profile = new Profile(UUID.randomUUID(), UUID.randomUUID());

        profile.setDailyStatus("Читаю книгу в парке", Instant.now().plus(2, ChronoUnit.HOURS));
        assertEquals("Читаю книгу в парке", profile.getDailyStatusText());
        assertNotNull(profile.getDailyStatusExpiresAt());

        profile.setDailyStatus("Читаю книгу в парке", Instant.now().minus(2, ChronoUnit.HOURS));
        assertNull(profile.getDailyStatusText());
        assertNull(profile.getDailyStatusExpiresAt());
    }

    private static CommunicationTag tag(String code) {
        return new CommunicationTag(UUID.randomUUID(), code, "pace", "Темп", code, "...", 1);
    }
}
