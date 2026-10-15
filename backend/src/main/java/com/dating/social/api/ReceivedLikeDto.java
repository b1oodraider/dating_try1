package com.dating.social.api;

import com.dating.profiles.api.PublicProfileDto;

import java.time.Instant;

/**
 * @param superlike highlight: the person used a super like
 */
public record ReceivedLikeDto(PublicProfileDto profile, boolean superlike, Instant likedAt) {
}
