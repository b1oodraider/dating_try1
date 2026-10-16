package com.dating.safety.application;

import com.dating.common.errors.ApiException;
import com.dating.safety.domain.Restriction;
import com.dating.safety.domain.RestrictionKind;
import com.dating.safety.persistence.RestrictionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks before an action (like, message). Depends on nothing but its table, so any module can use it.
 */
@Component
public class RestrictionGuard {

    private final RestrictionRepository repository;

    public RestrictionGuard(RestrictionRepository repository) {
        this.repository = repository;
    }

    /**
     * @throws ApiException 403 ACCOUNT_RESTRICTED, details { kind, until: ISO time or null = until lifted }
     */
    public void requireAllowed(UUID accountId, RestrictionKind kind) {
        Optional<Restriction> longest = repository.active(accountId).stream()
                .filter(r -> r.kind() == kind)
                .max(Comparator.comparing(r -> r.endsAt() == null ? Instant.MAX : r.endsAt()));
        if (longest.isPresent()) {
            Map<String, Object> details = new HashMap<>();
            details.put("kind", kind.name());
            details.put("until", longest.get().endsAt() == null ? null : longest.get().endsAt().toString());
            throw ApiException.withDetails("ACCOUNT_RESTRICTED", message(kind), HttpStatus.FORBIDDEN, details);
        }
    }

    private static String message(RestrictionKind kind) {
        return switch (kind) {
            case LIKES -> "Лайки временно ограничены";
            case MESSAGES -> "Отправка сообщений временно ограничена";
            default -> "Действие ограничено модерацией";
        };
    }
}
