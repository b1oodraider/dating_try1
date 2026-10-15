package com.dating.social.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.social.application.BlockService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Blocks a person. The path takes the profile id because account ids are never exposed.
 */
@RestController
public class BlockController {

    private final BlockService blockService;

    public BlockController(BlockService blockService) {
        this.blockService = blockService;
    }

    @PostMapping("/api/v1/accounts/{profileId}/block")
    public ResponseEntity<Void> block(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID profileId) {
        blockService.block(user.accountId(), profileId);
        return ResponseEntity.noContent().build();
    }
}
