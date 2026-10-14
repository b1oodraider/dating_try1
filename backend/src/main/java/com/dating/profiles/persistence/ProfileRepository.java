package com.dating.profiles.persistence;

import com.dating.profiles.domain.Profile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfileRepository extends JpaRepository<Profile, UUID>, JpaSpecificationExecutor<Profile> {

    Optional<Profile> findByAccountId(UUID accountId);

    /**
     * Serializes concurrent changes of one profile (photo limit, primary photo, publication).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Profile p where p.accountId = :accountId")
    Optional<Profile> findByAccountIdForUpdate(@Param("accountId") UUID accountId);

    /**
     * Race-safe lazy creation of the (empty) profile on first access.
     */
    @Modifying
    @Query(value = """
            INSERT INTO profiles (id, account_id, publication_status, visibility_status, created_at, updated_at)
            VALUES (:id, :accountId, 'DRAFT', 'PUBLIC', now(), now())
            ON CONFLICT (account_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("accountId") UUID accountId);
}
