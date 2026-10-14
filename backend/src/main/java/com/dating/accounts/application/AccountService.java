package com.dating.accounts.application;

import com.dating.accounts.domain.Account;
import com.dating.accounts.domain.AccountStatus;
import com.dating.accounts.persistence.AccountRepository;
import com.dating.common.errors.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public Account getAccountById(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("Аккаунт не найден"));
    }

    @Transactional
    public Account findOrCreateAccount(String phoneHash, String phoneMasked) {
        return accountRepository.findByPhoneHash(phoneHash)
                .orElseGet(() -> {
                    Account newAccount = new Account(UUID.randomUUID(), phoneHash, phoneMasked);
                    return accountRepository.save(newAccount);
                });
    }

    @Transactional
    public void markAccountDeleted(UUID accountId) {
        Account account = getAccountById(accountId);
        account.setStatus(AccountStatus.DELETED);
        accountRepository.save(account);
    }
}
