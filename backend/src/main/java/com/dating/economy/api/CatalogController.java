package com.dating.economy.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.economy.application.CatalogQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Active catalog items for clients: the test panel picks items from it, gacha and shop screens render it.
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogQueryService catalogQueryService;

    public CatalogController(CatalogQueryService catalogQueryService) {
        this.catalogQueryService = catalogQueryService;
    }

    @GetMapping
    public ResponseEntity<List<CatalogItemDto>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @RequestParam(required = false) String kind,
                                                     @RequestParam(required = false) String category) {
        return ResponseEntity.ok(catalogQueryService.activeItems(user.accountId(), kind, category));
    }
}
