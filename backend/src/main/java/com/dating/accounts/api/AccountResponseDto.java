package com.dating.accounts.api;

import com.dating.accounts.domain.Account;
import com.dating.accounts.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * @param testAccount QA account; clients show the testing panel for it
 */
public record AccountResponseDto(
        UUID id,
        String phoneMasked,
        AccountStatus status,
        Instant createdAt,
        boolean testAccount
) {
    public static AccountResponseDto from(Account account) {
        return new AccountResponseDto(
                account.getId(),
                account.getPhoneMasked(),
                account.getStatus(),
                account.getCreatedAt(),
                account.isTestAccount()
        );
    }
}
