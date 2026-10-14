package com.dating.discovery.api;

import java.util.Set;

public record DiscoveryQueryDto(
        String city,
        Integer minAge,
        Integer maxAge,
        Set<String> tagCodes,
        String tagLogic, // "AND" or "OR"
        int page,
        int limit
) {
    public DiscoveryQueryDto {
        if (page < 0) page = 0;
        if (limit <= 0 || limit > 50) limit = 20;
        if (tagLogic == null || (!tagLogic.equalsIgnoreCase("AND") && !tagLogic.equalsIgnoreCase("OR"))) {
            tagLogic = "OR";
        }
    }
}
