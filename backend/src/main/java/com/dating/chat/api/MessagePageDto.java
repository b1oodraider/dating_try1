package com.dating.chat.api;

import java.util.List;
import java.util.UUID;

/**
 * @param items      newest first
 * @param nextBefore pass as {@code before} to load older messages; null when there are no more
 */
public record MessagePageDto(List<MessageDto> items, UUID nextBefore) {
}
