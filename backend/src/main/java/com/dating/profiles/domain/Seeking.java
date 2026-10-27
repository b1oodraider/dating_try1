package com.dating.profiles.domain;

/** Who a person looks for; stored as text, keep in sync with migration 036. */
public enum Seeking {
    MALE,
    FEMALE,
    /** Both men and women. */
    ANY;

    public boolean accepts(Gender gender) {
        return this == ANY || name().equals(gender.name());
    }
}
