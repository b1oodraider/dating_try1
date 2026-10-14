package com.dating.profiles.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.profiles.application.ProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
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
}
