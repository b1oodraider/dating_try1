package com.dating.profiles.api;

import com.dating.profiles.domain.Interest;

/** @param comment the owner's comment on it («латте, капучино»); null in the dictionary and when none */
public record InterestDto(String code, String label, String category, String categoryLabel, String comment) {

    public static InterestDto from(Interest interest) {
        return from(interest, null);
    }

    public static InterestDto from(Interest interest, String comment) {
        return new InterestDto(interest.getCode(), interest.getLabel(), interest.getCategory().getCode(),
                interest.getCategory().getLabel(), comment);
    }
}
