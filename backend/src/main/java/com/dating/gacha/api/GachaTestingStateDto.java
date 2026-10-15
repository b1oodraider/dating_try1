package com.dating.gacha.api;

import com.dating.gacha.domain.PityState;

import java.util.List;
import java.util.Map;

/**
 * Gacha state of a test account.
 *
 * @param forcedQueue banner code → entry ids the next pulls on that banner will return, in order
 * @param pity        pity group → counters
 * @param pulls       pulls in the history
 */
public record GachaTestingStateDto(Map<String, List<String>> forcedQueue, Map<String, PityState> pity, long pulls) {
}
