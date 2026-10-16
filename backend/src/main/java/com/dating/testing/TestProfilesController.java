package com.dating.testing;

import com.dating.accounts.application.AccountService;
import com.dating.common.errors.ApiException;
import com.dating.common.security.AuthenticatedUser;
import com.dating.profiles.api.PhotoDto;
import com.dating.profiles.api.ProfileUpdateDto;
import com.dating.testing.TestProfilesService.CreateRequest;
import com.dating.testing.TestProfilesService.SeedProfileDto;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seed profiles for QA: create (DRAFT) → upload photos one by one → publish. Callable by test accounts only.
 */
@RestController
@RequestMapping("/api/v1/testing/profiles")
@ConditionalOnProperty(prefix = "app.testing", name = "enabled", havingValue = "true")
public class TestProfilesController {

    private final TestProfilesService service;
    private final AccountService accountService;

    public TestProfilesController(TestProfilesService service, AccountService accountService) {
        this.service = service;
        this.accountService = accountService;
    }

    @GetMapping
    public List<SeedProfileDto> list(@AuthenticationPrincipal AuthenticatedUser user) {
        requireTestAccount(user);
        return service.list();
    }

    @PostMapping
    public ResponseEntity<SeedProfileDto> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @Valid @RequestBody CreateRequest request) {
        requireTestAccount(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{accountId}")
    public SeedProfileDto update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID accountId,
                                 @Valid @RequestBody ProfileUpdateDto request) {
        requireTestAccount(user);
        return service.update(accountId, request);
    }

    @PostMapping(value = "/{accountId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoDto addPhoto(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID accountId,
                             @RequestParam("file") MultipartFile file) {
        requireTestAccount(user);
        return service.addPhoto(accountId, file);
    }

    @PostMapping("/{accountId}/publish")
    public SeedProfileDto publish(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID accountId) {
        requireTestAccount(user);
        return service.publish(accountId);
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID accountId) {
        requireTestAccount(user);
        service.delete(accountId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public Map<String, Integer> deleteAll(@AuthenticationPrincipal AuthenticatedUser user) {
        requireTestAccount(user);
        return Map.of("deleted", service.deleteAll());
    }

    private void requireTestAccount(AuthenticatedUser user) {
        if (!accountService.isTestAccount(user.accountId())) {
            throw ApiException.forbidden("TEST_ACCOUNT_REQUIRED", "Доступно только тестовым аккаунтам");
        }
    }
}
