package com.dating.accounts.application;

import com.dating.accounts.domain.Account;
import com.dating.accounts.domain.AccountStatus;
import com.dating.accounts.persistence.AccountRepository;
import com.dating.common.errors.ApiException;
import com.dating.productevents.application.ProductEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountService {

    /** phone_hash prefix of phone-less QA seed accounts (real hashes are hex, so they never collide). */
    public static final String SEED_PHONE_HASH_PREFIX = "seed:";

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

    @Transactional(readOnly = true)
    public java.util.Optional<Account> findByPhoneHash(String phoneHash) {
        return accountRepository.findByPhoneHash(phoneHash);
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
     * Test accounts are defined by configuration (app.testing.test-phones) and re-evaluated on every login.
     */
    @Transactional
    public void updateTestAccountFlag(Account account, boolean testAccount) {
        if (account.isTestAccount() != testAccount) {
            account.setTestAccount(testAccount);
            accountRepository.save(account);
        }
    }

    /**
     * Creates a test account without a phone (QA seed profile). It cannot log in; it only exists so that
     * test accounts have ready-made profiles in discovery. Marked by the {@link #SEED_PHONE_HASH_PREFIX}.
     */
    @Transactional
    public UUID createSeedAccount() {
        Account account = findOrCreateAccount(SEED_PHONE_HASH_PREFIX + UUID.randomUUID(), "тестовый профиль");
        account.setTestAccount(true);
        accountRepository.save(account);
        return account.getId();
    }

    @Transactional(readOnly = true)
    public List<UUID> activeSeedAccountIds() {
        return accountRepository.findAllByPhoneHashStartingWithAndStatusOrderByCreatedAtAsc(
                SEED_PHONE_HASH_PREFIX, AccountStatus.ACTIVE).stream().map(Account::getId).toList();
    }

    @Transactional(readOnly = true)
    public boolean isActiveSeedAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .filter(Account::isActive)
                .filter(a -> a.getPhoneHash().startsWith(SEED_PHONE_HASH_PREFIX))
                .isPresent();
    }

    @Transactional(readOnly = true)
    public boolean isTestAccount(UUID accountId) {
        return accountRepository.findById(accountId).map(Account::isTestAccount).orElse(false);
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
