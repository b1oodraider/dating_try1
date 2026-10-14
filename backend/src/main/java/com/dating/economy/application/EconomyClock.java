package com.dating.economy.application;

import com.dating.economy.persistence.EconomyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * "Now" for economy decisions of one account: premium expiry and allowance periods.
 * Real accounts always get real time; test accounts can be moved forward via the testing API
 * to see how premium and periodic consumables behave next week / next month.
 */
@Component
public class EconomyClock {

    private final EconomyRepository repository;

    public EconomyClock(EconomyRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Instant now(UUID accountId) {
        return Instant.now().plusSeconds(repository.clockOffsetSeconds(accountId));
    }

    @Transactional(readOnly = true)
    public Duration offset(UUID accountId) {
        return Duration.ofSeconds(repository.clockOffsetSeconds(accountId));
    }

    @Transactional
    public void advance(UUID accountId, Duration by) {
        repository.setClockOffsetSeconds(accountId, repository.clockOffsetSeconds(accountId) + by.toSeconds());
    }

    @Transactional
    public void reset(UUID accountId) {
        repository.setClockOffsetSeconds(accountId, 0);
    }
}
