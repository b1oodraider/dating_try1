package com.dating.accounts.api;

import com.dating.accounts.domain.Account;
import com.dating.accounts.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record AccountResponseDto(
        UUID id,
        String phoneMasked,
        AccountStatus status,
        Instant createdAt
) {
    public static AccountResponseDto from(Account account) {
        return new AccountResponseDto(
                account.getId(),
                account.getPhoneMasked(),
                account.getStatus(),
                account.getCreatedAt()
        );
    }
}
