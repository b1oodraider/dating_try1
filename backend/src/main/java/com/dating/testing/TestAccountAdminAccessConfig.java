package com.dating.testing;

import com.dating.accounts.application.AccountService;
import com.dating.common.errors.ApiException;
import com.dating.common.security.AdminAccess;
import com.dating.common.testing.TestAccountsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TEMPORARY {@link AdminAccess} until roles exist: test accounts may edit content while the testing mode is on
 * (app.testing.enabled=true); everyone else, and everyone when the testing mode is off, gets 403 ROLE_REQUIRED.
 * <p>
 * Backs off as soon as another {@code AdminAccess} bean (real roles) is registered.
 */
@Configuration
public class TestAccountAdminAccessConfig {

    @Bean
    @ConditionalOnMissingBean(AdminAccess.class)
    public AdminAccess testAccountAdminAccess(TestAccountsProperties testing, AccountService accountService) {
        return accountId -> {
            if (!testing.enabled() || !accountService.isTestAccount(accountId)) {
                throw ApiException.forbidden("ROLE_REQUIRED", "Недостаточно прав: нужна роль редактора контента");
            }
        };
    }
}
