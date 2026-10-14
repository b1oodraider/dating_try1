package com.dating.economy.application;

import com.dating.common.errors.ApiException;
import com.dating.economy.domain.CatalogItem;
import com.dating.economy.domain.CosmeticSlot;
import com.dating.economy.persistence.EconomyRepository;
import com.dating.profiles.api.DecorationDto;
import com.dating.profiles.application.ProfileDecorationQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Equipping owned cosmetics into slots and exposing the public ones on profiles.
 */
@Service
public class CosmeticsService implements ProfileDecorationQuery {

    private static final List<String> PUBLIC_SLOTS = Arrays.stream(CosmeticSlot.values())
            .filter(CosmeticSlot::publiclyVisible)
            .map(Enum::name)
            .toList();

    private final EconomyRepository repository;
    private final EconomyService economyService;

    public CosmeticsService(EconomyRepository repository, EconomyService economyService) {
        this.repository = repository;
        this.economyService = economyService;
    }

    @Transactional
    public void equip(UUID accountId, String slotName, String itemCode) {
        CosmeticSlot slot = parseSlot(slotName);
        CatalogItem item = economyService.requireItem(itemCode);
        if (!slot.name().equals(item.equipSlot())) {
            throw ApiException.badRequest("WRONG_SLOT", "Этот предмет нельзя надеть в слот " + slot.name());
        }
        if (repository.balance(accountId, itemCode) <= 0) {
            throw ApiException.forbidden("ITEM_NOT_OWNED", "У вас нет этого предмета");
        }
        repository.equip(accountId, slot.name(), itemCode);
    }

    @Transactional
    public void unequip(UUID accountId, String slotName) {
        repository.unequip(accountId, parseSlot(slotName).name());
    }

    @Transactional(readOnly = true)
    public Map<String, String> equipped(UUID accountId) {
        return repository.equipped(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Map<String, DecorationDto>> publicDecorations(Collection<UUID> accountIds) {
        Map<UUID, Map<String, DecorationDto>> result = new HashMap<>();
        for (EconomyRepository.DecorationRow row : repository.decorations(accountIds, PUBLIC_SLOTS)) {
            CatalogItem item = row.item();
            result.computeIfAbsent(row.accountId(), id -> new HashMap<>())
                    .put(row.slot(), new DecorationDto(item.code(), item.title(), item.rarity(), item.attributes()));
        }
        return result;
    }

    private static CosmeticSlot parseSlot(String slotName) {
        return CosmeticSlot.parse(slotName)
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_SLOT", "Неизвестный слот: " + slotName));
    }
}
