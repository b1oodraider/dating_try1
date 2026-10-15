package com.dating.admin.api;

import com.dating.admin.application.AdminAuditLog;
import com.dating.admin.application.AdminRolesService;
import com.dating.admin.application.AdminRolesService.AdminAccount;
import com.dating.admin.application.AdminRolesService.RolesOverview;
import com.dating.common.security.AuthenticatedUser;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Staff roles and the audit log (ADMIN only).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminRolesController {

    private final AdminRolesService rolesService;
    private final AdminAuditLog auditLog;
    private final RoleAccess roleAccess;

    public AdminRolesController(AdminRolesService rolesService, AdminAuditLog auditLog, RoleAccess roleAccess) {
        this.rolesService = rolesService;
        this.auditLog = auditLog;
        this.roleAccess = roleAccess;
    }

    @GetMapping("/roles")
    public RolesOverview roles(@AuthenticationPrincipal AuthenticatedUser user) {
        return rolesService.overview(user.accountId());
    }

    @GetMapping("/accounts/by-phone")
    public AdminAccount accountByPhone(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam String phone) {
        return rolesService.accountByPhone(user.accountId(), phone);
    }

    @GetMapping("/accounts/{accountId}")
    public AdminAccount account(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID accountId) {
        return rolesService.account(user.accountId(), accountId);
    }

    @PutMapping("/accounts/{accountId}/roles/{role}")
    public AdminAccount grant(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID accountId,
                              @PathVariable String role) {
        return rolesService.grant(user.accountId(), accountId, role);
    }

    @DeleteMapping("/accounts/{accountId}/roles/{role}")
    public AdminAccount revoke(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID accountId,
                               @PathVariable String role) {
        return rolesService.revoke(user.accountId(), accountId, role);
    }

    /**
     * Newest first; entityType: ACCOUNT_ROLE, TAG, TAG_CATEGORY, GOAL, CATALOG_ITEM, AUTHOR, ACHIEVEMENT,
     * PREMIUM_COMPOSITION.
     */
    @GetMapping("/audit")
    public List<AdminAuditLog.Entry> audit(@AuthenticationPrincipal AuthenticatedUser user,
                                           @RequestParam(required = false) String entityType,
                                           @RequestParam(required = false) String entityId,
                                           @RequestParam(defaultValue = "50") int limit) {
        roleAccess.require(user.accountId(), Role.ADMIN);
        return auditLog.list(blankToNull(entityType), blankToNull(entityId), limit);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
