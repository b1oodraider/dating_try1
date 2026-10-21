package com.dating.profiles.api;

import com.dating.profiles.domain.Artist;

public record ArtistDto(long id, String name) {

    public static ArtistDto from(Artist artist) {
        return new ArtistDto(artist.getId(), artist.getName());
    }
}
