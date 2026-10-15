package com.dating.accounts.application;

import com.dating.accounts.persistence.AccountRoleRepository;
import com.dating.common.errors.ApiException;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Staff roles of accounts. Checks always hit the DB, so a revoked role stops working on the next request.
 * Writes are called by the admin API (which also writes the audit log), the bootstrap on login and
 * the testing API.
 */
@Service
public class RoleService implements RoleAccess {

    private final AccountRoleRepository repository;

    public RoleService(AccountRoleRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Role> rolesOf(UUID accountId) {
        return repository.rolesOf(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAny(UUID accountId, Role... roles) {
        Set<Role> granted = repository.rolesOf(accountId);
        return Arrays.stream(roles).anyMatch(granted::contains);
    }

    @Override
    @Transactional(readOnly = true)
    public void require(UUID accountId, Role... roles) {
        if (!hasAny(accountId, roles)) {
            throw ApiException.roleRequired(Arrays.stream(roles).map(Enum::name).toList());
        }
    }

    /**
     * @return true if the role was added now (false: already granted)
     */
    @Transactional
    public boolean grant(UUID accountId, Role role, UUID grantedBy) {
        return repository.grant(accountId, role, grantedBy);
    }

    /**
     * Revokes a role. The last active ADMIN cannot lose the role, otherwise nobody could manage roles
     * any more (the config bootstrap would be the only way back).
     *
     * @return true if the role was removed now (false: the account did not have it)
     * @throws ApiException 409 LAST_ADMIN
     */
    @Transactional
    public boolean revoke(UUID accountId, Role role) {
        if (role == Role.ADMIN) {
            List<UUID> admins = repository.lockActiveHolders(Role.ADMIN);
            if (admins.size() == 1 && admins.contains(accountId)) {
                throw ApiException.conflict("LAST_ADMIN", "Нельзя снять роль с единственного администратора");
            }
        }
        return repository.revoke(accountId, role);
    }

    /**
     * Testing API: a test account drops its own role. No last-admin guard, a test account is never
     * the one that keeps the system manageable.
     */
    @Transactional
    public boolean revokeForTesting(UUID accountId, Role role) {
        return repository.revoke(accountId, role);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Set<Role>> allAssignments() {
        return repository.allAssignments();
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        repository.deleteAll(event.accountId());
    }
}
