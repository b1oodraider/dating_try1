package com.dating.discovery.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Replaces the whole list. Per-word rules are checked in the service after normalization.
 */
public record ExclusionWordsUpdateDto(
        @NotNull(message = "Передайте список слов (может быть пустым)")
        @Size(max = 100, message = "Слишком длинный список")
        List<String> words
) {}
