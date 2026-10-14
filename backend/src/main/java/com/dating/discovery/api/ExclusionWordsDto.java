package com.dating.discovery.api;

import java.util.List;

/**
 * @param active false when the words are saved but not applied (premium is not active)
 */
public record ExclusionWordsDto(List<String> words, int maxWords, boolean active) {}
