package com.dating.profiles.api;

import com.dating.profiles.domain.CommunicationTag;

public record TagResponseDto(
        String code,
        String category,
        String categoryLabel,
        String label,
        String description,
        int sortOrder
) {
    public static TagResponseDto from(CommunicationTag tag) {
        return new TagResponseDto(
                tag.getCode(),
                tag.getCategory(),
                tag.getCategoryLabel(),
                tag.getLabel(),
                tag.getDescription(),
                tag.getSortOrder()
        );
    }
}
