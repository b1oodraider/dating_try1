package com.dating.safety.application;

import com.dating.accounts.application.AccountService;
import com.dating.accounts.domain.AccountStatus;
import com.dating.admin.application.AdminAuditLog;
import com.dating.common.errors.ApiException;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.safety.api.SafetyAdminController;
import com.dating.safety.domain.Restriction;
import com.dating.safety.domain.SanctionPolicy;
import com.dating.safety.persistence.RestrictionRepository;
import com.dating.social.domain.ReportReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SafetyAdminService {

    private final SanctionService sanctionService;
    private final RestrictionRepository repository;
    private final AccountService accountService;
    private final RoleAccess roleAccess;
    private final AdminAuditLog auditLog;

    public SafetyAdminService(SanctionService sanctionService, RestrictionRepository repository,
                              AccountService accountService, RoleAccess roleAccess, AdminAuditLog auditLog) {
        this.sanctionService = sanctionService;
        this.repository = repository;
        this.accountService = accountService;
        this.roleAccess = roleAccess;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public List<Restriction> history(UUID actor, UUID accountId) {
        roleAccess.require(actor, Role.MODERATOR, Role.ADMIN);
        accountService.getAccountById(accountId);
        return sanctionService.history(accountId);
    }

    /**
     * @throws ApiException 404 ACCOUNT_NOT_FOUND, 409 ACCOUNT_DELETED
     */
    @Transactional
    public Restriction restrict(UUID actor, UUID accountId, SafetyAdminController.RestrictRequest request) {
        roleAccess.require(actor, Role.MODERATOR, Role.ADMIN);
        if (accountService.getAccountById(accountId).getStatus() == AccountStatus.DELETED) {
            throw ApiException.conflict("ACCOUNT_DELETED", "Аккаунт удалён");
        }
        Restriction created = sanctionService.restrict(accountId, request.kind(), request.hours(), SanctionService.MANUAL,
                "MODERATOR", actor, blankToNull(request.note()));
        auditLog.record(actor, "RESTRICT", "ACCOUNT_RESTRICTION", created.id().toString(), null, created);
        return created;
    }

    @Transactional
    public Restriction lift(UUID actor, UUID restrictionId, String note) {
        roleAccess.require(actor, Role.ADMIN);
        Restriction before = repository.find(restrictionId).orElse(null);
        Restriction after = sanctionService.lift(restrictionId, actor, blankToNull(note));
        auditLog.record(actor, "LIFT", "ACCOUNT_RESTRICTION", restrictionId.toString(), before, after);
        return after;
    }

    @Transactional(readOnly = true)
    public List<SanctionPolicy> policies(UUID actor) {
        roleAccess.require(actor, Role.MODERATOR, Role.ADMIN);
        return repository.policies();
    }

    /**
     * @throws ApiException 400 VALIDATION_ERROR (unknown reason)
     */
    @Transactional
    public SanctionPolicy updatePolicy(UUID actor, String reason, SafetyAdminController.PolicyRequest request) {
        roleAccess.require(actor, Role.ADMIN);
        String code = java.util.Arrays.stream(ReportReason.values()).map(Enum::name)
                .filter(n -> n.equalsIgnoreCase(reason)).findFirst().orElse(null);
        if (code == null) {
            throw ApiException.validation("VALIDATION_ERROR", "Неизвестная причина жалобы",
                    Map.of("reason", "Одна из причин жалоб: SPAM, SCAM, …"));
        }
        SanctionPolicy before = repository.policy(code).orElse(null);
        SanctionPolicy policy = new SanctionPolicy(code, request.windowDays(), request.holdOnReports(),
                request.steps().stream()
                        .map(s -> new SanctionPolicy.Step(s.restrictions().stream()
                                .map(r -> new SanctionPolicy.StepRestriction(r.kind(), r.hours()))
                                .toList()))
                        .toList());
        repository.savePolicy(policy, actor);
        SanctionPolicy after = repository.policy(code).orElseThrow();
        auditLog.record(actor, "UPDATE", "SANCTION_POLICY", code, before, after);
        return after;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
