package com.dating.profiles;

import com.dating.profiles.application.ProfileService;
import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.persistence.CommunicationTagRepository;
import com.dating.profiles.persistence.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private CommunicationTagRepository tagRepository;

    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(profileRepository, tagRepository);
    }

    @Test
    @DisplayName("Completeness calculation increases with filled fields")
    void testCompletenessCalculation() {
        Profile profile = new Profile(UUID.randomUUID(), UUID.randomUUID(), "Анна", 24, "Москва");
        int initialScore = profileService.calculateCompleteness(profile);
        assertTrue(initialScore >= 35, "Basic fields should provide baseline score");

        profile.setAboutText("Люблю долгие прогулки по набережной и кофе без сахара.");
        profile.setTags(Set.of(
                new CommunicationTag(UUID.randomUUID(), "PACE_ASYNCHRONOUS", "pace", "Темп", "Асинхронно", "...", 1),
                new CommunicationTag(UUID.randomUUID(), "FORMAT_WALK_FIRST", "format", "Формат", "Прогулка", "...", 2),
                new CommunicationTag(UUID.randomUUID(), "CALLS_TEXT_ONLY", "calls", "Звонки", "Только текст", "...", 3)
        ));

        int fullScore = profileService.calculateCompleteness(profile);
        assertEquals(100, fullScore);
    }

    @Test
    @DisplayName("24-hour daily status expires after 24h")
    void testDailyStatusExpiration() {
        Profile profile = new Profile(UUID.randomUUID(), UUID.randomUUID(), "Анна", 24, "Москва");

        // Active status
        profile.setDailyStatus("Читаю книгу в парке", Instant.now().plus(2, ChronoUnit.HOURS));
        assertEquals("Читаю книгу в парке", profile.getDailyStatusText());

        // Expired status
        profile.setDailyStatus("Читаю книгу в парке", Instant.now().minus(2, ChronoUnit.HOURS));
        assertNull(profile.getDailyStatusText(), "Expired daily status must return null");
    }

    @Test
    @DisplayName("Draft profile can be published")
    void testPublicationStatus() {
        Profile profile = new Profile(UUID.randomUUID(), UUID.randomUUID(), "Анна", 24, "Москва");
        assertEquals(PublicationStatus.DRAFT, profile.getPublicationStatus());

        profile.setPublicationStatus(PublicationStatus.PUBLISHED);
        assertEquals(PublicationStatus.PUBLISHED, profile.getPublicationStatus());

        profile.setPublicationStatus(PublicationStatus.HIDDEN);
        assertEquals(PublicationStatus.HIDDEN, profile.getPublicationStatus());
    }
}
