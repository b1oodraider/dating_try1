package com.dating.media.persistence;

import com.dating.media.domain.ProfilePhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfilePhotoRepository extends JpaRepository<ProfilePhoto, UUID> {

    List<ProfilePhoto> findAllByProfileIdAndDeletedAtIsNullOrderBySortOrderAsc(UUID profileId);

    List<ProfilePhoto> findAllByProfileIdInAndDeletedAtIsNullOrderBySortOrderAsc(Collection<UUID> profileIds);

    List<ProfilePhoto> findAllByProfileId(UUID profileId);

    Optional<ProfilePhoto> findByIdAndProfileIdAndDeletedAtIsNull(UUID id, UUID profileId);

    Optional<ProfilePhoto> findByIdAndDeletedAtIsNull(UUID id);

    long countByProfileIdAndDeletedAtIsNull(UUID profileId);

    @Query("select coalesce(max(p.sortOrder), -1) from ProfilePhoto p where p.profileId = :profileId and p.deletedAt is null")
    int findMaxSortOrder(@Param("profileId") UUID profileId);
}
