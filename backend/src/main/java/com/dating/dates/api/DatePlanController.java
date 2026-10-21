package com.dating.dates.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.dates.application.DatePlanService;
import com.dating.dates.application.VenueService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Date plan in a chat and the places to pick from (docs/date-plan.md). Staff edit places under /admin/venues
 * (CONTENT_EDITOR or ADMIN); test accounts can add sample places with /testing/venues (TestVenuesController).
 */
@RestController
@RequestMapping("/api/v1")
public class DatePlanController {

    public record PlanRequest(@NotNull(message = "Добавьте хотя бы один пункт") List<DatePlanService.ItemInput> items) {
    }

    public record EditRequest(@NotNull(message = "Добавьте хотя бы один пункт") List<DatePlanService.ItemInput> items,
                              @NotNull(message = "Укажите версию плана") Integer version) {
    }

    public record VenueRequest(
            @NotBlank(message = "Укажите название") @Size(max = 120) String name,
            @Size(max = 200) String address,
            @NotNull(message = "Укажите широту") Double latitude,
            @NotNull(message = "Укажите долготу") Double longitude,
            @NotBlank(message = "Укажите категорию") String category,
            @Size(max = 300) String description,
            Boolean sponsored,
            @Size(max = 160) String advertiser,
            @Size(max = 64) String erid,
            Boolean active
    ) {
        VenueService.Change change() {
            return new VenueService.Change(name.trim(), address, latitude, longitude, category, description,
                    Boolean.TRUE.equals(sponsored), advertiser, erid, active == null || active);
        }
    }

    private final DatePlanService plans;
    private final VenueService venues;

    public DatePlanController(DatePlanService plans, VenueService venues) {
        this.plans = plans;
        this.venues = venues;
    }

    /** The plan in play in this chat: { plan: DatePlan | null }. */
    @GetMapping("/matches/{matchId}/date-plan")
    public Map<String, Object> current(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID matchId) {
        var plan = plans.current(user.accountId(), matchId).orElse(null);
        return plan == null ? java.util.Collections.singletonMap("plan", null) : Map.of("plan", plan);
    }

    @PostMapping("/matches/{matchId}/date-plan")
    public ResponseEntity<DatePlanService.Plan> propose(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @PathVariable UUID matchId,
                                                        @Valid @RequestBody PlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(plans.propose(user.accountId(), matchId, request.items()));
    }

    @GetMapping("/date-plans/{planId}")
    public DatePlanService.Plan get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID planId) {
        return plans.get(user.accountId(), planId);
    }

    @PutMapping("/date-plans/{planId}")
    public DatePlanService.Plan edit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID planId,
                                     @Valid @RequestBody EditRequest request) {
        return plans.edit(user.accountId(), planId, request.version(), request.items());
    }

    @PostMapping("/date-plans/{planId}/accept")
    public DatePlanService.Plan accept(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID planId) {
        return plans.accept(user.accountId(), planId);
    }

    @PostMapping("/date-plans/{planId}/decline")
    public DatePlanService.Plan decline(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID planId) {
        return plans.decline(user.accountId(), planId);
    }

    @PostMapping("/date-plans/{planId}/cancel")
    public DatePlanService.Plan cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID planId) {
        return plans.cancel(user.accountId(), planId);
    }

    @GetMapping("/date-plans/{planId}/history")
    public List<DatePlanService.HistoryEntry> history(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable UUID planId) {
        return plans.history(user.accountId(), planId);
    }

    // ------------------------------------------------------------- venues

    @GetMapping("/venues")
    public List<VenueService.Venue> venues(@AuthenticationPrincipal AuthenticatedUser user,
                                           @RequestParam double latitude, @RequestParam double longitude) {
        return venues.near(user.accountId(), latitude, longitude);
    }

    @GetMapping("/admin/venues")
    public List<VenueService.AdminVenue> allVenues(@AuthenticationPrincipal AuthenticatedUser user) {
        return venues.all(user.accountId());
    }

    @PostMapping("/admin/venues")
    public ResponseEntity<VenueService.AdminVenue> createVenue(@AuthenticationPrincipal AuthenticatedUser user,
                                                               @Valid @RequestBody VenueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(venues.create(user.accountId(), request.change()));
    }

    @PutMapping("/admin/venues/{venueId}")
    public VenueService.AdminVenue updateVenue(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable UUID venueId, @Valid @RequestBody VenueRequest request) {
        return venues.update(user.accountId(), venueId, request.change());
    }
}
