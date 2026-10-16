package com.dating.safety.application;

import com.dating.safety.persistence.BehaviorRepository.ReactionEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LikeSpamRuleTest {

    private static final LikeSpamProperties P = new LikeSpamProperties(true, 10, Duration.ofMinutes(10), 0.9,
            Duration.ofSeconds(3), Duration.ofSeconds(1), 0.8, 1, 168, Duration.ofDays(30));

    /** Newest first: {@code likes} likes then passes, {@code gapMs} apart, each looked at for {@code lookedMs}. */
    private static List<ReactionEvent> window(int likes, int passes, long gapMs, Integer lookedMs, boolean opened) {
        List<ReactionEvent> events = new ArrayList<>();
        Instant at = Instant.parse("2026-10-16T12:00:00Z");
        for (int i = 0; i < likes + passes; i++) {
            events.add(new ReactionEvent(UUID.randomUUID(), i < likes ? "LIKE" : "PASS", at.minusMillis(i * gapMs),
                    lookedMs, opened));
        }
        return events;
    }

    @Test
    void fastLikesOfEverybodyAreSpam() {
        assertTrue(LikeSpamDetector.isSpam(window(10, 0, 1_000, null, false), P));
        assertTrue(LikeSpamDetector.isSpam(window(9, 1, 2_000, null, false), P));
    }

    @Test
    void speedAloneIsNotEnough_aSelectiveFastSwiperIsFine() {
        assertFalse(LikeSpamDetector.isSpam(window(5, 5, 500, null, false), P));
    }

    @Test
    void slowLikesWithoutViewDataAreFine() {
        assertFalse(LikeSpamDetector.isSpam(window(10, 0, 8_000, null, false), P));
    }

    @Test
    void slowButBlindLikesAreSpamWhenTheClientReportsViews() {
        assertTrue(LikeSpamDetector.isSpam(window(10, 0, 8_000, 300, false), P));
        assertFalse(LikeSpamDetector.isSpam(window(10, 0, 8_000, 300, true), P), "opened the details");
        assertFalse(LikeSpamDetector.isSpam(window(10, 0, 8_000, 4_000, false), P), "looked long enough");
    }

    @Test
    void anIncompleteWindowIsNeverJudged() {
        assertFalse(LikeSpamDetector.isSpam(window(9, 0, 100, null, false), P));
    }
}
