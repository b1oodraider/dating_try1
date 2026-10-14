package com.dating.auth.application;

import com.dating.auth.persistence.AuthChallengeRepository;
import com.dating.auth.persistence.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Keeps auth tables from growing forever: expired challenges and dead sessions are purged hourly.
 */
@Component
public class AuthMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(AuthMaintenanceJob.class);
    private static final Duration RETENTION = Duration.ofDays(1);

    private final AuthChallengeRepository challengeRepository;
    private final SessionRepository sessionRepository;

    public AuthMaintenanceJob(AuthChallengeRepository challengeRepository, SessionRepository sessionRepository) {
        this.challengeRepository = challengeRepository;
        this.sessionRepository = sessionRepository;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    @Transactional
    public void purgeStaleAuthData() {
        Instant now = Instant.now();
        int challenges = challengeRepository.deleteExpiredBefore(now.minus(RETENTION));
        int sessions = sessionRepository.deleteStale(now, now.minus(RETENTION));
        if (challenges > 0 || sessions > 0) {
            log.info("Purged {} expired OTP challenges and {} stale sessions", challenges, sessions);
        }
    }
}
