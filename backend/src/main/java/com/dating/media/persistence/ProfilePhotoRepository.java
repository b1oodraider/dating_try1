package com.dating.media.persistence;

import com.dating.media.domain.ProfilePhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfilePhotoRepository extends JpaRepository<ProfilePhoto, UUID> {
    List<ProfilePhoto> findAllByProfileIdAndDeletedAtIsNullOrderBySortOrderAsc(UUID profileId);
    Optional<ProfilePhoto> findByIdAndProfileIdAndDeletedAtIsNull(UUID id, UUID profileId);
    long countByProfileIdAndDeletedAtIsNull(UUID profileId);
}
