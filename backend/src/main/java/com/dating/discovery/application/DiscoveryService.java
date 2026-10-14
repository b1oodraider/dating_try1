package com.dating.discovery.application;

import com.dating.discovery.api.DiscoveryPageResponseDto;
import com.dating.discovery.api.DiscoveryQueryDto;
import com.dating.profiles.api.ProfileResponseDto;
import com.dating.profiles.domain.CommunicationTag;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.domain.VisibilityStatus;
import com.dating.profiles.persistence.ProfileRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DiscoveryService {

    private final ProfileRepository profileRepository;

    public DiscoveryService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public DiscoveryPageResponseDto searchProfiles(UUID callerAccountId, DiscoveryQueryDto query) {
        PageRequest pageRequest = PageRequest.of(
                query.page(),
                query.limit(),
                Sort.by(Sort.Direction.DESC, "updatedAt")
        );

        String cityFilter = (query.city() != null && !query.city().equalsIgnoreCase("ALL"))
                ? query.city()
                : null;

        Page<Profile> page = profileRepository.findDiscoveryProfiles(
                PublicationStatus.PUBLISHED,
                VisibilityStatus.PUBLIC,
                callerAccountId,
                cityFilter,
                query.minAge(),
                query.maxAge(),
                pageRequest
        );

        List<Profile> content = page.getContent();

        // Apply functional tag filtering (AND / OR) if tags requested
        if (query.tagCodes() != null && !query.tagCodes().isEmpty()) {
            boolean isAndLogic = "AND".equalsIgnoreCase(query.tagLogic());
            Set<String> requiredCodes = query.tagCodes();

            content = content.stream()
                    .filter(p -> {
                        Set<String> profileCodes = p.getTags().stream()
                                .map(CommunicationTag::getCode)
                                .collect(Collectors.toSet());

                        if (isAndLogic) {
                            return profileCodes.containsAll(requiredCodes);
                        } else {
                            return profileCodes.stream().anyMatch(requiredCodes::contains);
                        }
                    })
                    .toList();
        }

        List<ProfileResponseDto> items = content.stream()
                .map(p -> ProfileResponseDto.from(p, 100))
                .toList();

        return new DiscoveryPageResponseDto(
                items,
                query.page(),
                query.limit(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
