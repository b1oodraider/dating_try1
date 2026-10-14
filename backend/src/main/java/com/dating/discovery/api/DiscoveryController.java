package com.dating.discovery.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.discovery.application.DiscoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

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
            @RequestParam(required = false) Integer minAge,
            @RequestParam(required = false) Integer maxAge,
            @RequestParam(required = false) Set<String> tagCodes,
            @RequestParam(required = false, defaultValue = "OR") String tagLogic,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int limit
    ) {
        UUID callerAccountId = user != null ? user.accountId() : null;
        DiscoveryQueryDto query = new DiscoveryQueryDto(city, minAge, maxAge, tagCodes, tagLogic, page, limit);
        DiscoveryPageResponseDto result = discoveryService.searchProfiles(callerAccountId, query);
        return ResponseEntity.ok(result);
    }
}
