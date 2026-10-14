package com.dating.discovery.api;

import com.dating.profiles.api.PublicProfileDto;

import java.util.List;

public record DiscoveryPageResponseDto(
        List<PublicProfileDto> items,
        int page,
        int limit,
        long totalElements,
        int totalPages
) {}
