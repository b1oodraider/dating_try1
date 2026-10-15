package com.dating.admin.application;

import com.dating.accounts.application.AccountService;
import com.dating.accounts.application.RoleService;
import com.dating.accounts.domain.Account;
import com.dating.accounts.domain.AccountStatus;
import com.dating.auth.domain.PhoneNumbers;
import com.dating.common.errors.ApiException;
import com.dating.common.security.Role;
import com.dating.common.util.Hashing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Granting and revoking staff roles (ADMIN only), with an audit entry per change.
 * Phones are stored only as hashes, so an account is found either by id or by a phone that is
 * normalized and hashed exactly as on login.
 */
@Service
public class AdminRolesService {

    public static final String ENTITY = "ACCOUNT_ROLE";

    public record AdminAccount(UUID accountId, String phoneMasked, AccountStatus status, boolean testAccount,
                               List<String> roles, Instant createdAt) {
    }

    public record RolesOverview(List<String> available, List<AdminAccount> accounts) {
    }

    private final RoleService roleService;
    private final AccountService accountService;
    private final AdminAuditLog auditLog;

    public AdminRolesService(RoleService roleService, AccountService accountService, AdminAuditLog auditLog) {
        this.roleService = roleService;
        this.accountService = accountService;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public RolesOverview overview(UUID actor) {
        roleService.require(actor, Role.ADMIN);
        List<AdminAccount> accounts = roleService.allAssignments().entrySet().stream()
                .map(e -> view(accountService.getAccountById(e.getKey()), e.getValue()))
                .toList();
        return new RolesOverview(Arrays.stream(Role.values()).map(Enum::name).toList(), accounts);
    }

    @Transactional(readOnly = true)
    public AdminAccount account(UUID actor, UUID accountId) {
        roleService.require(actor, Role.ADMIN);
        return view(accountService.getAccountById(accountId));
    }

    @Transactional(readOnly = true)
    public AdminAccount accountByPhone(UUID actor, String rawPhone) {
        roleService.require(actor, Role.ADMIN);
        String phone = PhoneNumbers.normalize(rawPhone).orElseThrow(() -> ApiException.validation(
                "INVALID_PHONE", "Некорректный номер телефона",
                Map.of("phone", "Укажите номер в формате +7 999 123-45-67")));
        return accountService.findByPhoneHash(Hashing.sha256Hex(phone))
                .map(this::view)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Аккаунт с таким номером не найден"));
    }

    @Transactional
    public AdminAccount grant(UUID actor, UUID accountId, String roleName) {
        roleService.require(actor, Role.ADMIN);
        Role role = parse(roleName);
        Account account = requireActive(accountId);
        Set<Role> before = roleService.rolesOf(accountId);
        if (roleService.grant(accountId, role, actor)) {
            auditLog.record(actor, "GRANT", ENTITY, accountId.toString(), names(before),
                    names(roleService.rolesOf(accountId)));
        }
        return view(account);
    }

    @Transactional
    public AdminAccount revoke(UUID actor, UUID accountId, String roleName) {
        roleService.require(actor, Role.ADMIN);
        Role role = parse(roleName);
        Account account = accountService.getAccountById(accountId);
        Set<Role> before = roleService.rolesOf(accountId);
        if (roleService.revoke(accountId, role)) {
            auditLog.record(actor, "REVOKE", ENTITY, accountId.toString(), names(before),
                    names(roleService.rolesOf(accountId)));
        }
        return view(account);
    }

    /**
     * Testing API: a test account gives itself a role or drops it (audited as TESTING_*).
     */
    @Transactional
    public void setOwnRoleForTesting(UUID accountId, String roleName, boolean granted) {
        Role role = parse(roleName);
        Set<Role> before = roleService.rolesOf(accountId);
        boolean changed = granted ? roleService.grant(accountId, role, accountId) : roleService.revokeForTesting(accountId, role);
        if (changed) {
            auditLog.record(accountId, granted ? "TESTING_GRANT" : "TESTING_REVOKE", ENTITY, accountId.toString(),
                    names(before), names(roleService.rolesOf(accountId)));
        }
    }

    private Account requireActive(UUID accountId) {
        Account account = accountService.getAccountById(accountId);
        if (!account.isActive()) {
            throw ApiException.conflict("ACCOUNT_NOT_ACTIVE", "Роль можно выдать только активному аккаунту");
        }
        return account;
    }

    private static Role parse(String roleName) {
        return Role.parse(roleName).orElseThrow(() -> ApiException.badRequest("UNKNOWN_ROLE",
                "Неизвестная роль: " + roleName + ". Доступны: " + String.join(", ",
                        Arrays.stream(Role.values()).map(Enum::name).toList())));
    }

    private AdminAccount view(Account account) {
        return view(account, roleService.rolesOf(account.getId()));
    }

    private static AdminAccount view(Account account, Collection<Role> roles) {
        return new AdminAccount(account.getId(), account.getPhoneMasked(), account.getStatus(), account.isTestAccount(),
                names(roles), account.getCreatedAt());
    }

    private static List<String> names(Collection<Role> roles) {
        return roles.stream().sorted().map(Enum::name).toList();
    }
}
