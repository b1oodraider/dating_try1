package com.dating.profiles.api;

import com.dating.profiles.domain.Interest;

/**
 * @param emoji   shown before the label (☕); null when the interest has none
 * @param comment the owner's comment on it («латте, капучино»); null in the dictionary and when none
 */
public record InterestDto(String code, String label, String emoji, String category, String categoryLabel,
                          String comment) {

    public static InterestDto from(Interest interest) {
        return from(interest, null);
    }

    public static InterestDto from(Interest interest, String comment) {
        return new InterestDto(interest.getCode(), interest.getLabel(), interest.getEmoji(),
                interest.getCategory().getCode(), interest.getCategory().getLabel(), comment);
    }
}
