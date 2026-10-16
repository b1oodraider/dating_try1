package com.dating.testing;

import com.dating.accounts.api.AccountResponseDto;
import com.dating.accounts.application.AccountService;
import com.dating.admin.application.AdminRolesService;
import com.dating.common.errors.ApiException;
import com.dating.common.security.AuthenticatedUser;
import com.dating.common.security.RoleAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * QA: a test account gives itself staff roles to try the admin API without a real admin.
 * Exists only with app.testing.enabled=true; callable by test accounts only; audited as TESTING_GRANT/REVOKE.
 * Responds with the account (as GET /me), so clients can refresh the admin entry point.
 */
@RestController
@RequestMapping("/api/v1/testing/roles")
@ConditionalOnProperty(prefix = "app.testing", name = "enabled", havingValue = "true")
public class TestRolesController {

    private final AccountService accountService;
    private final AdminRolesService rolesService;
    private final RoleAccess roleAccess;

    public TestRolesController(AccountService accountService, AdminRolesService rolesService, RoleAccess roleAccess) {
        this.accountService = accountService;
        this.rolesService = rolesService;
        this.roleAccess = roleAccess;
    }

    @PostMapping("/{role}")
    public AccountResponseDto grant(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String role) {
        UUID accountId = requireTestAccount(user);
        rolesService.setOwnRoleForTesting(accountId, role, true);
        return account(accountId);
    }

    @DeleteMapping("/{role}")
    public AccountResponseDto revoke(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String role) {
        UUID accountId = requireTestAccount(user);
        rolesService.setOwnRoleForTesting(accountId, role, false);
        return account(accountId);
    }

    private UUID requireTestAccount(AuthenticatedUser user) {
        if (!accountService.isTestAccount(user.accountId())) {
            throw ApiException.forbidden("TEST_ACCOUNT_REQUIRED", "Доступно только тестовым аккаунтам");
        }
        return user.accountId();
    }

    private AccountResponseDto account(UUID accountId) {
        return AccountResponseDto.from(accountService.getAccountById(accountId), roleAccess.rolesOf(accountId));
    }
}
