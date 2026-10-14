package com.dating.accounts.api;

import com.dating.accounts.application.AccountService;
import com.dating.accounts.domain.Account;
import com.dating.common.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<AccountResponseDto> getCurrentAccount(@AuthenticationPrincipal AuthenticatedUser user) {
        Account account = accountService.getAccountById(user.accountId());
        return ResponseEntity.ok(AccountResponseDto.from(account));
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal AuthenticatedUser user) {
        accountService.markAccountDeleted(user.accountId());
        return ResponseEntity.noContent().build();
    }
}
