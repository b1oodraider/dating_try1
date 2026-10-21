package com.dating.profiles.api;

import com.dating.profiles.domain.Interest;

public record InterestDto(String code, String label, String category, String categoryLabel) {

    public static InterestDto from(Interest interest) {
        return new InterestDto(interest.getCode(), interest.getLabel(), interest.getCategory().getCode(),
                interest.getCategory().getLabel());
    }
}
