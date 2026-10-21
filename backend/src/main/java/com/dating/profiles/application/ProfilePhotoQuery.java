package com.dating.profiles.application;

import com.dating.profiles.api.PhotoDto;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read access to profile photos, implemented by the media module.
 * Declared here so that profiles does not depend on media (media already depends on profiles).
 */
public interface ProfilePhotoQuery {

    /** All my photos, including the ones under face review (owner and moderator views). */
    List<PhotoDto> photosOf(UUID profileId);

    /** What other people see: photos under face review are left out. */
    Map<UUID, List<PhotoDto>> photosOf(Collection<UUID> profileIds);

    long countPhotos(UUID profileId);
}
