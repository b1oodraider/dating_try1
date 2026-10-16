package com.dating.economy.application;

import com.dating.common.errors.ApiException;
import com.dating.economy.api.CatalogItemDto;
import com.dating.economy.domain.ItemKind;
import com.dating.economy.persistence.CatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Public catalog: active items with availability and author. Limited items are listed even outside their
 * window; {@code availableNow} (in the caller's economy time) tells whether offers can grant them now.
 */
@Service
public class CatalogQueryService {

    private final CatalogRepository repository;
    private final EconomyClock clock;

    public CatalogQueryService(CatalogRepository repository, EconomyClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CatalogItemDto> activeItems(UUID accountId, String kind, String category) {
        if (kind != null && Arrays.stream(ItemKind.values()).noneMatch(k -> k.name().equals(kind))) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Тип предмета: CONSUMABLE или COSMETIC");
        }
        Instant now = clock.now(accountId);
        return repository.entries(true, kind, category).stream()
                .map(entry -> CatalogItemDto.from(entry, now))
                .toList();
    }
}
