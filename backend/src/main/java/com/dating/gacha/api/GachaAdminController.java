package com.dating.gacha.api;

import com.dating.common.security.AdminAccess;
import com.dating.common.security.AuthenticatedUser;
import com.dating.gacha.application.GachaAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Banner and pool management. Every call requires {@link AdminAccess#requireContentEditor}
 * (403 ROLE_REQUIRED); until roles exist only test accounts pass, and only with app.testing.enabled=true.
 */
@RestController
@RequestMapping("/api/v1/admin/gacha")
public class GachaAdminController {

    private final GachaAdminService adminService;
    private final AdminAccess adminAccess;

    public GachaAdminController(GachaAdminService adminService, AdminAccess adminAccess) {
        this.adminService = adminService;
        this.adminAccess = adminAccess;
    }

    @GetMapping("/banners")
    public List<AdminBannerDto> list(@AuthenticationPrincipal AuthenticatedUser user) {
        editor(user);
        return adminService.listBanners();
    }

    @GetMapping("/banners/{code}")
    public AdminBannerDto get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code) {
        editor(user);
        return adminService.getBanner(code);
    }

    @PostMapping("/banners")
    public ResponseEntity<AdminBannerDto> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @Valid @RequestBody AdminBannerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createBanner(editor(user), request));
    }

    @PutMapping("/banners/{code}")
    public AdminBannerDto update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                                 @Valid @RequestBody AdminBannerRequest request) {
        return adminService.updateBanner(editor(user), code, request);
    }

    @DeleteMapping("/banners/{code}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code) {
        adminService.deleteBanner(editor(user), code);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/banners/{code}/pool/{entryId}")
    public AdminBannerDto putEntry(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                                   @PathVariable String entryId, @Valid @RequestBody AdminPoolEntryRequest request) {
        return adminService.putPoolEntry(editor(user), code, entryId, request);
    }

    @DeleteMapping("/banners/{code}/pool/{entryId}")
    public AdminBannerDto deleteEntry(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String code,
                                      @PathVariable String entryId) {
        return adminService.deletePoolEntry(editor(user), code, entryId);
    }

    private UUID editor(AuthenticatedUser user) {
        adminAccess.requireContentEditor(user.accountId());
        return user.accountId();
    }
}
