package com.dating.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * Role checks for staff endpoints. Lives in common so any module can guard its admin actions
 * without depending on the accounts module.
 * <p>
 * Usage: {@code roleAccess.require(accountId, Role.CONTENT_EDITOR, Role.ADMIN);}
 */
public interface RoleAccess {

    Set<Role> rolesOf(UUID accountId);

    /**
     * @return true if the account has at least one of the roles
     */
    boolean hasAny(UUID accountId, Role... roles);

    /**
     * @throws com.dating.common.errors.ApiException 403 ROLE_REQUIRED (details.roles) if the account has none of them
     */
    void require(UUID accountId, Role... roles);
}
