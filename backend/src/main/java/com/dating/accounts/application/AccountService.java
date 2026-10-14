package com.dating.accounts.application;

import com.dating.accounts.domain.Account;
import com.dating.accounts.persistence.AccountRepository;
import com.dating.common.errors.ApiException;
import com.dating.productevents.application.ProductEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductEventPublisher productEvents;

    public AccountService(AccountRepository accountRepository,
                          ApplicationEventPublisher eventPublisher,
                          ProductEventPublisher productEvents) {
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
        this.productEvents = productEvents;
    }

    @Transactional(readOnly = true)
    public Account getAccountById(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Аккаунт не найден"));
    }

    @Transactional
    public Account findOrCreateAccount(String phoneHash, String phoneMasked) {
        int inserted = accountRepository.insertIfAbsent(UUID.randomUUID(), phoneHash, phoneMasked);
        Account account = accountRepository.findByPhoneHash(phoneHash)
                .orElseThrow(() -> new IllegalStateException("Account must exist after insertIfAbsent"));
        if (inserted > 0) {
            productEvents.publish("account_created", Map.of());
            eventPublisher.publishEvent(new AccountCreatedEvent(account.getId()));
        }
        return account;
    }

    /**
     * Deletes all personal data of the account in one transaction: photos (files after commit),
     * profile, sessions; the account row is anonymized so the phone can be registered again.
     */
    @Transactional
    public void deleteAccount(UUID accountId) {
        Account account = getAccountById(accountId);
        eventPublisher.publishEvent(new AccountDeletedEvent(accountId));
        account.markDeleted();
        accountRepository.save(account);
        productEvents.publish("account_deleted", Map.of());
    }
}
