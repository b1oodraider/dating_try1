package com.dating.auth.persistence;

import com.dating.auth.domain.AuthChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthChallengeRepository extends JpaRepository<AuthChallenge, UUID> {

    /**
     * Row lock: parallel guesses for one challenge are serialized, so the attempt limit cannot be raced.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthChallenge c where c.id = :id")
    Optional<AuthChallenge> findByIdForUpdate(@Param("id") UUID id);

    Optional<AuthChallenge> findTopByPhoneHashOrderByCreatedAtDesc(String phoneHash);

    long countByPhoneHashAndCreatedAtAfter(String phoneHash, Instant since);

    @Modifying
    @Query("delete from AuthChallenge c where c.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
