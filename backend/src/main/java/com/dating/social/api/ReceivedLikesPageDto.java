package com.dating.social.api;

import java.util.List;

/**
 * Same paging shape as the discovery feed. Super likes come first, then the newest likes.
 */
public record ReceivedLikesPageDto(
        List<ReceivedLikeDto> items,
        int page,
        int limit,
        long totalElements,
        int totalPages
) {
}
