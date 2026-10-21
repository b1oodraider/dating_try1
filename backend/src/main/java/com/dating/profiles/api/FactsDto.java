package com.dating.profiles.api;

import com.dating.profiles.domain.Profile;

import java.util.List;

/**
 * Profile facts (codes, labels from GET /profile-options); null / empty = not said.
 */
public record FactsDto(
        String kidsHas,
        String kidsPlans,
        String smoking,
        String alcohol,
        String sport,
        List<String> pets,
        String profession,
        String educationLevel,
        String university,
        List<String> languages
) {
    public static FactsDto from(Profile p) {
        return new FactsDto(p.getKidsHas(), p.getKidsPlans(), p.getSmoking(), p.getAlcohol(), p.getSport(),
                p.getPets(), p.getProfession(), p.getEducationLevel(), p.getUniversity(), p.getLanguages());
    }
}
