package com.dating.discovery.api;

import java.util.List;

public record ExclusionWordsDto(List<String> words, int maxWords) {}
