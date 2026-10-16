package com.dating.profiles.application;

import com.dating.profiles.api.DecorationDto;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Publicly visible equipped cosmetics (photo frame, profile status, …), keyed by slot.
 * Implemented by the economy module; declared here so profiles/discovery do not depend on it.
 */
public interface ProfileDecorationQuery {

    Map<UUID, Map<String, DecorationDto>> publicDecorations(Collection<UUID> accountIds);

    default Map<String, DecorationDto> publicDecorations(UUID accountId) {
        return publicDecorations(java.util.List.of(accountId)).getOrDefault(accountId, Map.of());
    }
}
