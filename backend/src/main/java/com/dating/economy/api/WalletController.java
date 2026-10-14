package com.dating.economy.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.economy.application.AllowanceService;
import com.dating.economy.application.EconomyService;
import com.dating.economy.application.PremiumService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/wallet")
public class WalletController {

    private final AllowanceService allowanceService;
    private final EconomyService economyService;
    private final PremiumService premiumService;

    public WalletController(AllowanceService allowanceService, EconomyService economyService,
                            PremiumService premiumService) {
        this.allowanceService = allowanceService;
        this.economyService = economyService;
        this.premiumService = premiumService;
    }

    @GetMapping
    public ResponseEntity<WalletDto> getWallet(@AuthenticationPrincipal AuthenticatedUser user) {
        UUID accountId = user.accountId();
        allowanceService.ensureAllowances(accountId);

        Instant until = premiumService.premiumUntil(accountId).orElse(null);
        boolean active = until != null && until.isAfter(Instant.now());
        var items = economyService.balances(accountId).stream()
                .map(b -> new WalletDto.Item(b.itemCode(), b.kind(), b.quantity()))
                .toList();
        return ResponseEntity.ok(new WalletDto(new WalletDto.Premium(active, until), premiumService.premiumFeatures(), items));
    }
}
