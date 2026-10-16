package com.dating.economy.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.economy.application.WalletQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/wallet")
public class WalletController {

    private final WalletQueryService walletQueryService;

    public WalletController(WalletQueryService walletQueryService) {
        this.walletQueryService = walletQueryService;
    }

    @GetMapping
    public ResponseEntity<WalletDto> getWallet(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(walletQueryService.wallet(user.accountId()));
    }
}
