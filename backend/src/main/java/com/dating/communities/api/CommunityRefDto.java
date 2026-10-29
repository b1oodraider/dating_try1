package com.dating.communities.api;

/**
 * A group on someone's profile.
 *
 * @param shared the viewer is subscribed to it too (highlighted, and counted in «Общее»)
 */
public record CommunityRefDto(long id, String title, String emoji, boolean shared) {
}
