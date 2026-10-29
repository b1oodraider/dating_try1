package com.dating.discovery.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.discovery.application.DiscoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Requires authentication (see SecurityConfig): profiles of a dating service must not be scraped anonymously.
 */
@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {

    private final DiscoveryService discoveryService;

    public DiscoveryController(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @GetMapping("/profiles")
    public ResponseEntity<DiscoveryPageResponseDto> getProfiles(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Integer cityId,
            @RequestParam(required = false) Integer radiusKm,
            @RequestParam(required = false) Integer minAge,
            @RequestParam(required = false) Integer maxAge,
            @RequestParam(required = false) Set<String> tagCodes,
            @RequestParam(required = false) String tagLogic,
            @RequestParam(required = false) Integer minHeight,
            @RequestParam(required = false) Integer maxHeight,
            @RequestParam(required = false) Set<String> goalCodes,
            @RequestParam(required = false) Set<Long> communityIds,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int limit
    ) {
        DiscoveryQueryDto query = new DiscoveryQueryDto(city, minAge, maxAge, tagCodes, TagLogic.parse(tagLogic),
                minHeight, maxHeight, goalCodes, page, limit, cityId, radiusKm, communityIds);
        return ResponseEntity.ok(discoveryService.searchProfiles(user.accountId(), query));
    }
}
