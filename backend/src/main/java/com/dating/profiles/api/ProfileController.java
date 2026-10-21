package com.dating.profiles.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.profiles.application.ArtistSearchService;
import com.dating.profiles.application.ProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ProfileController {

    private final ProfileService profileService;
    private final ArtistSearchService artistSearch;

    public ProfileController(ProfileService profileService, ArtistSearchService artistSearch) {
        this.profileService = profileService;
        this.artistSearch = artistSearch;
    }

    @GetMapping("/me/profile")
    public ResponseEntity<MyProfileDto> getMyProfile(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(profileService.getMyProfile(user.accountId()));
    }

    @PutMapping("/me/profile")
    public ResponseEntity<MyProfileDto> updateMyProfile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ProfileUpdateDto dto
    ) {
        return ResponseEntity.ok(profileService.updateProfile(user.accountId(), dto));
    }

    /** Approximate device location (rounded to ~1 km); distances in the feed come from it. */
    public record LocationDto(@NotNull Double latitude, @NotNull Double longitude) {
    }

    @PutMapping("/me/location")
    public MyProfileDto updateLocation(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody LocationDto location) {
        return profileService.updateLocation(user.accountId(), location.latitude(), location.longitude());
    }

    @DeleteMapping("/me/location")
    public MyProfileDto clearLocation(@AuthenticationPrincipal AuthenticatedUser user) {
        return profileService.clearLocation(user.accountId());
    }

    @PostMapping("/me/profile/publish")
    public ResponseEntity<MyProfileDto> publishProfile(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(profileService.publishProfile(user.accountId()));
    }

    @PostMapping("/me/profile/hide")
    public ResponseEntity<MyProfileDto> hideProfile(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(profileService.hideProfile(user.accountId()));
    }

    @GetMapping("/goals")
    public ResponseEntity<List<GoalDto>> listGoals() {
        return ResponseEntity.ok(profileService.listGoals());
    }

    @GetMapping("/tags")
    public ResponseEntity<List<TagResponseDto>> listTags() {
        return ResponseEntity.ok(profileService.listActiveTags());
    }

    @GetMapping("/interests")
    public List<InterestGroupDto> listInterests() {
        return profileService.listInterests();
    }

    /** Signed-in only: a miss may trigger a MusicBrainz lookup. */
    @GetMapping("/artists")
    public List<ArtistDto> searchArtists(@RequestParam(name = "q", defaultValue = "") String query) {
        return artistSearch.search(query);
    }
}
