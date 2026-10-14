package com.dating.auth.persistence;

import com.dating.accounts.domain.AccountStatus;
import com.dating.auth.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    /**
     * A session authenticates only while it is not revoked, not expired and its account is ACTIVE.
     */
    @Query("""
            select s from Session s
            where s.tokenHash = :tokenHash
              and s.revokedAt is null
              and s.expiresAt > :now
              and exists (select a.id from Account a where a.id = s.accountId and a.status = :status)
            """)
    Optional<Session> findUsable(@Param("tokenHash") String tokenHash,
                                 @Param("now") Instant now,
                                 @Param("status") AccountStatus status);

    @Modifying
    @Query("delete from Session s where s.accountId = :accountId")
    int deleteAllByAccountId(@Param("accountId") UUID accountId);

    @Modifying
    @Query("delete from Session s where s.expiresAt < :now or s.revokedAt < :revokedBefore")
    int deleteStale(@Param("now") Instant now, @Param("revokedBefore") Instant revokedBefore);
}
