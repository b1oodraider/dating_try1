package com.dating.profiles;

import com.dating.profiles.application.ProfileService;
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
        assertEquals(0, ProfileService.calculateCompleteness(profile, 0));
        assertEquals(List.of("displayName", "age", "city", "photos"),
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
        profile.setAge(26);
        profile.setCity("Москва");
        profile.setAboutText("Люблю долгие прогулки и плёночную фотографию");
        profile.replaceTags(Set.of(tag("A"), tag("B"), tag("C")));

        assertTrue(ProfileService.missingForPublish(profile, 1).isEmpty());
        assertEquals(100, ProfileService.calculateCompleteness(profile, 1));
        assertEquals(80, ProfileService.calculateCompleteness(profile, 0), "photo is worth 20%");
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
