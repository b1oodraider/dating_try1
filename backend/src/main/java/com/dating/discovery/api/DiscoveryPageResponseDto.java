package com.dating.discovery.api;

import com.dating.profiles.api.ProfileResponseDto;
import java.util.List;

public record DiscoveryPageResponseDto(
        List<ProfileResponseDto> items,
        int page,
        int limit,
        long totalElements,
        int totalPages
) {}
