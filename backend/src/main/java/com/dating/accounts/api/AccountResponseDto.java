package com.dating.accounts.api;

import com.dating.accounts.domain.Account;
import com.dating.accounts.domain.AccountStatus;
import com.dating.common.security.Role;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * @param testAccount QA account; clients show the testing panel for it
 * @param roles       staff roles (ADMIN, CONTENT_EDITOR, MODERATOR, SUPPORT); clients show admin tools for them
 */
public record AccountResponseDto(
        UUID id,
        String phoneMasked,
        AccountStatus status,
        Instant createdAt,
        boolean testAccount,
        List<String> roles
) {
    public static AccountResponseDto from(Account account, Collection<Role> roles) {
        return new AccountResponseDto(
                account.getId(),
                account.getPhoneMasked(),
                account.getStatus(),
                account.getCreatedAt(),
                account.isTestAccount(),
                roles.stream().sorted().map(Enum::name).toList()
        );
    }
}
