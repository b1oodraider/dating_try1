package com.dating.accounts.persistence;

import com.dating.accounts.domain.Account;
import com.dating.accounts.domain.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByPhoneHash(String phoneHash);

    List<Account> findAllByPhoneHashStartingWithAndStatusOrderByCreatedAtAsc(String prefix, AccountStatus status);

    /**
     * Race-safe "create if absent": two concurrent first logins with the same phone
     * must not fail with a unique-constraint violation.
     *
     * @return 1 if a new account was inserted, 0 if it already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO accounts (id, phone_hash, phone_masked, status, phone_verified_at, created_at, updated_at)
            VALUES (:id, :phoneHash, :phoneMasked, 'ACTIVE', now(), now(), now())
            ON CONFLICT (phone_hash) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("phoneHash") String phoneHash,
                       @Param("phoneMasked") String phoneMasked);
}
