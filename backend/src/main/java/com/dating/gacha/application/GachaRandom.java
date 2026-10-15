package com.dating.gacha.application;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * Source of randomness for pulls: server-side {@link SecureRandom}, never anything the client can influence.
 * Tests of the roll logic pass a seeded generator to {@link com.dating.gacha.domain.GachaRoller} directly;
 * integration tests make results deterministic with the testing API's forced queue.
 */
@Component
public class GachaRandom {

    /** Thread-safe: SecureRandom is safe for concurrent use. */
    private final RandomGenerator generator = new SecureRandom();

    public RandomGenerator generator() {
        return generator;
    }
}
