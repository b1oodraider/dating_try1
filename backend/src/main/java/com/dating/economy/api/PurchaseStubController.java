package com.dating.economy.api;

import com.dating.common.errors.ApiException;
import com.dating.common.security.AuthenticatedUser;
import com.dating.economy.application.EconomyService;
import com.dating.economy.application.WalletQueryService;
import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Purchase STUB until real payments exist (docs/TODO.md, «История покупок»): the «Купить» button grants the product
 * without any payment. shortcut: free for everyone while app.purchases.stub-enabled=true; turn it off (or replace
 * this controller) before real users can install the app.
 */
@RestController
@RequestMapping("/api/v1/me/purchases")
public class PurchaseStubController {

    public record StubPurchaseRequest(@NotBlank(message = "Укажите товар") String product) {
    }

    /** Products the shop pages offer. */
    private static final Map<String, List<Reward>> PRODUCTS = Map.of(
            "PREMIUM_MONTH", List.of(new Reward.PremiumDays(30)),
            "SUPERLIKE_PACK", List.of(new Reward.Item("SUPERLIKE", 5)),
            "BOOST_PACK", List.of(new Reward.Item("BOOST", 3)));

    private final EconomyService economyService;
    private final WalletQueryService walletQueryService;
    private final boolean enabled;

    public PurchaseStubController(EconomyService economyService, WalletQueryService walletQueryService,
                                  @Value("${app.purchases.stub-enabled:true}") boolean enabled) {
        this.economyService = economyService;
        this.walletQueryService = walletQueryService;
        this.enabled = enabled;
    }

    /** 200 Wallet after the purchase ; 404 PRODUCT_NOT_FOUND ; 403 PURCHASES_UNAVAILABLE when the stub is off. */
    @PostMapping("/stub")
    public WalletDto buy(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody StubPurchaseRequest request) {
        if (!enabled) {
            throw ApiException.forbidden("PURCHASES_UNAVAILABLE", "Покупки пока недоступны");
        }
        List<Reward> rewards = PRODUCTS.get(request.product());
        if (rewards == null) {
            throw new ApiException("PRODUCT_NOT_FOUND", "Такого товара нет", HttpStatus.NOT_FOUND);
        }
        RewardSource source = request.product().startsWith("PREMIUM") ? RewardSource.SUBSCRIPTION : RewardSource.PURCHASE;
        economyService.grant(user.accountId(), source, "stub:" + request.product(), "stub:" + UUID.randomUUID(), rewards);
        return walletQueryService.wallet(user.accountId());
    }
}
