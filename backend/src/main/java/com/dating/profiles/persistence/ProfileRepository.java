package com.dating.profiles.persistence;

import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.domain.VisibilityStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    Optional<Profile> findByAccountId(UUID accountId);

    @Query("""
        SELECT DISTINCT p FROM Profile p
        LEFT JOIN p.tags t
        WHERE p.publicationStatus = :pubStatus
          AND p.visibilityStatus = :visStatus
          AND (:excludeAccountId IS NULL OR p.accountId != :excludeAccountId)
          AND (:city IS NULL OR LOWER(p.city) = LOWER(:city))
          AND (:minAge IS NULL OR p.age >= :minAge)
          AND (:maxAge IS NULL OR p.age <= :maxAge)
    """)
    Page<Profile> findDiscoveryProfiles(
            @Param("pubStatus") PublicationStatus pubStatus,
            @Param("visStatus") VisibilityStatus visStatus,
            @Param("excludeAccountId") UUID excludeAccountId,
            @Param("city") String city,
            @Param("minAge") Integer minAge,
            @Param("maxAge") Integer maxAge,
            Pageable pageable
    );
}
