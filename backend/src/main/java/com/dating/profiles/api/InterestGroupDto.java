package com.dating.profiles.api;

import java.util.List;

/** GET /interests: the dictionary grouped by category, in display order. */
public record InterestGroupDto(String code, String label, List<InterestDto> items) {
}
