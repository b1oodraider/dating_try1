package com.dating.testing;

import com.dating.accounts.application.AccountService;
import com.dating.common.errors.ApiException;
import com.dating.common.security.AuthenticatedUser;
import com.dating.dates.application.VenueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Testing API: sample places for the date plan, visible to test accounts only. */
@RestController
@RequestMapping("/api/v1/testing/venues")
@ConditionalOnProperty(prefix = "app.testing", name = "enabled", havingValue = "true")
public class TestVenuesController {

    public record SamplesRequest(double latitude, double longitude) {
    }

    private final VenueService venues;
    private final AccountService accountService;

    public TestVenuesController(VenueService venues, AccountService accountService) {
        this.venues = venues;
        this.accountService = accountService;
    }

    /** Three test-only places around the point, one of them sponsored (to see the ad label). */
    @PostMapping
    public ResponseEntity<List<VenueService.AdminVenue>> samples(@AuthenticationPrincipal AuthenticatedUser user,
                                                                 @RequestBody SamplesRequest request) {
        if (!accountService.isTestAccount(user.accountId())) {
            throw ApiException.forbidden("TEST_ACCOUNT_REQUIRED", "Доступно только тестовым аккаунтам");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(venues.createSamples(request.latitude(), request.longitude()));
    }
}
