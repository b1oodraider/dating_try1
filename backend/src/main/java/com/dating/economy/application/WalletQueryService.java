package com.dating.economy.application;

import com.dating.economy.api.WalletDto;
import com.dating.economy.domain.CatalogItem;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the wallet view (premium, items, equipped cosmetics), granting due allowances first.
 * Not transactional itself: allowances are granted in their own transactions.
 */
@Service
public class WalletQueryService {

    private final AllowanceService allowanceService;
    private final EconomyService economyService;
    private final PremiumService premiumService;
    private final CosmeticsService cosmeticsService;
    private final EconomyClock clock;

    public WalletQueryService(AllowanceService allowanceService, EconomyService economyService,
                              PremiumService premiumService, CosmeticsService cosmeticsService, EconomyClock clock) {
        this.allowanceService = allowanceService;
        this.economyService = economyService;
        this.premiumService = premiumService;
        this.cosmeticsService = cosmeticsService;
        this.clock = clock;
    }

    public WalletDto wallet(UUID accountId) {
        allowanceService.ensureAllowances(accountId);

        Instant now = clock.now(accountId);
        Instant until = premiumService.premiumUntil(accountId).orElse(null);
        boolean active = until != null && until.isAfter(now);
        var items = economyService.balances(accountId).stream()
                .map(b -> {
                    CatalogItem i = b.item();
                    return new WalletDto.Item(i.code(), i.kind(), i.category(), i.title(), i.rarity(), i.equipSlot(),
                            i.attributes(), b.quantity(), b.periodicQuantity());
                })
                .toList();
        return new WalletDto(new WalletDto.Premium(active, until), premiumService.premiumFeatures(), items,
                cosmeticsService.equipped(accountId));
    }
}
