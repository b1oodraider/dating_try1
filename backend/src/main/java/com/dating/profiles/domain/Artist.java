package com.dating.profiles.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A music artist: seeded popular names plus ones found on MusicBrainz; up to 5 per profile. */
@Entity
@Table(name = "artists")
public class Artist {

    @Id
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    protected Artist() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
