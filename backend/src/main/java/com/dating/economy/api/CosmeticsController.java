package com.dating.economy.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.economy.application.CosmeticsService;
import com.dating.economy.application.WalletQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Equip / unequip owned cosmetics. Responds with the updated wallet.
 */
@RestController
@RequestMapping("/api/v1/me/cosmetics/equipped")
public class CosmeticsController {

    public record EquipRequest(@NotBlank(message = "Укажите предмет") String itemCode) {
    }

    private final CosmeticsService cosmeticsService;
    private final WalletQueryService walletQueryService;

    public CosmeticsController(CosmeticsService cosmeticsService, WalletQueryService walletQueryService) {
        this.cosmeticsService = cosmeticsService;
        this.walletQueryService = walletQueryService;
    }

    @PutMapping("/{slot}")
    public ResponseEntity<WalletDto> equip(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable String slot,
                                           @Valid @RequestBody EquipRequest request) {
        cosmeticsService.equip(user.accountId(), slot, request.itemCode());
        return ResponseEntity.ok(walletQueryService.wallet(user.accountId()));
    }

    @DeleteMapping("/{slot}")
    public ResponseEntity<WalletDto> unequip(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String slot) {
        cosmeticsService.unequip(user.accountId(), slot);
        return ResponseEntity.ok(walletQueryService.wallet(user.accountId()));
    }
}
