package com.dating.common.security;

import java.util.UUID;

/**
 * Port for back-office permissions, so content modules (gacha, catalog, …) do not depend on how roles are stored.
 * <p>
 * TEMPORARY: until roles exist, the only implementation is the testing fallback
 * ({@code com.dating.testing.TestAccountAdminAccessConfig}): test accounts are editors while
 * {@code app.testing.enabled=true}, everybody else is denied. The roles module replaces it by declaring its own
 * {@code AdminAccess} bean (the fallback backs off automatically).
 */
public interface AdminAccess {

    /**
     * @throws com.dating.common.errors.ApiException 403 ROLE_REQUIRED if the account may not edit content
     */
    void requireContentEditor(UUID accountId);
}
