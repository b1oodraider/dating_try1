package com.dating.social.api;

/**
 * @param count          likes waiting for my answer (super likes included)
 * @param superlikeCount of them, super likes
 */
public record ReceivedLikesCountDto(long count, long superlikeCount) {
}
