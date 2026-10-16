package com.dating.admin.application;

import com.dating.achievements.application.AchievementAdminService;
import com.dating.achievements.application.AchievementAdminService.Changes;
import com.dating.achievements.application.AchievementAdminService.Definition;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.economy.application.EconomyProperties.Allowance;
import com.dating.economy.application.PremiumCompositionService;
import com.dating.economy.application.PremiumCompositionService.Composition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Achievement definitions (CONTENT_EDITOR, ADMIN) and the premium composition (ADMIN only: it decides
 * what users pay for). Every write is audited.
 */
@Service
public class AdminEconomyService {

    /**
     * @param current  what is in effect now
     * @param defaults the config values a reset returns to
     */
    public record PremiumView(Composition current, Composition defaults) {
    }

    private final RoleAccess roleAccess;
    private final AchievementAdminService achievements;
    private final PremiumCompositionService premium;
    private final AdminAuditLog auditLog;

    public AdminEconomyService(RoleAccess roleAccess, AchievementAdminService achievements,
                               PremiumCompositionService premium, AdminAuditLog auditLog) {
        this.roleAccess = roleAccess;
        this.achievements = achievements;
        this.premium = premium;
        this.auditLog = auditLog;
    }

    // ---------------------------------------------------------------- achievements

    @Transactional(readOnly = true)
    public List<Definition> achievements(UUID actor) {
        roleAccess.require(actor, AdminContentService.EDITORS);
        return achievements.all();
    }

    @Transactional
    public Definition updateAchievement(UUID actor, String code, Changes changes) {
        roleAccess.require(actor, AdminContentService.EDITORS);
        Definition before = achievements.require(code);
        Definition after = achievements.update(code, changes);
        auditLog.record(actor, "UPDATE", "ACHIEVEMENT", code, before, after);
        return after;
    }

    // ---------------------------------------------------------------- premium composition

    @Transactional(readOnly = true)
    public PremiumView premium(UUID actor) {
        roleAccess.require(actor, Role.ADMIN);
        return new PremiumView(premium.load(), premium.configDefaults());
    }

    /**
     * @param features   null = keep
     * @param allowances null = keep
     */
    @Transactional
    public PremiumView updatePremium(UUID actor, List<String> features, List<Allowance> allowances) {
        roleAccess.require(actor, Role.ADMIN);
        Composition before = premium.load();
        Composition after = premium.update(features, allowances, actor);
        auditLog.record(actor, "UPDATE", "PREMIUM_COMPOSITION", "premium", before, after);
        return new PremiumView(after, premium.configDefaults());
    }

    @Transactional
    public PremiumView resetPremium(UUID actor) {
        roleAccess.require(actor, Role.ADMIN);
        Composition before = premium.load();
        Composition after = premium.resetToConfig();
        auditLog.record(actor, "RESET", "PREMIUM_COMPOSITION", "premium", before, after);
        return new PremiumView(after, premium.configDefaults());
    }
}
