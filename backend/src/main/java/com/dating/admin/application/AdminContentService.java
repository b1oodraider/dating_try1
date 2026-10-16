package com.dating.admin.application;

import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.profiles.application.ReferenceDataAdminService;
import com.dating.profiles.application.ReferenceDataAdminService.DeleteOutcome;
import com.dating.profiles.application.ReferenceDataAdminService.GoalFields;
import com.dating.profiles.application.ReferenceDataAdminService.TagFields;
import com.dating.profiles.persistence.ReferenceDataAdminRepository.CategoryRow;
import com.dating.profiles.persistence.ReferenceDataAdminRepository.GoalRow;
import com.dating.profiles.persistence.ReferenceDataAdminRepository.TagRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Communication cards (tags), tag categories and relationship goals for CONTENT_EDITOR and ADMIN.
 * Every write is audited with before/after snapshots in the same transaction.
 */
@Service
public class AdminContentService {

    static final Role[] EDITORS = {Role.CONTENT_EDITOR, Role.ADMIN};

    private final RoleAccess roleAccess;
    private final ReferenceDataAdminService referenceData;
    private final AdminAuditLog auditLog;

    public AdminContentService(RoleAccess roleAccess, ReferenceDataAdminService referenceData, AdminAuditLog auditLog) {
        this.roleAccess = roleAccess;
        this.referenceData = referenceData;
        this.auditLog = auditLog;
    }

    // ---------------------------------------------------------------- tags

    @Transactional(readOnly = true)
    public List<TagRow> tags(UUID actor) {
        roleAccess.require(actor, EDITORS);
        return referenceData.tags();
    }

    @Transactional
    public TagRow createTag(UUID actor, String code, TagFields fields) {
        roleAccess.require(actor, EDITORS);
        TagRow created = referenceData.createTag(code, fields);
        auditLog.record(actor, "CREATE", "TAG", code, null, created);
        return created;
    }

    @Transactional
    public TagRow updateTag(UUID actor, String code, TagFields fields) {
        roleAccess.require(actor, EDITORS);
        TagRow before = referenceData.requireTag(code);
        TagRow after = referenceData.updateTag(code, fields);
        auditLog.record(actor, "UPDATE", "TAG", code, before, after);
        return after;
    }

    @Transactional
    public DeleteOutcome deleteTag(UUID actor, String code) {
        roleAccess.require(actor, EDITORS);
        TagRow before = referenceData.requireTag(code);
        DeleteOutcome outcome = referenceData.deleteTag(code);
        auditLog.record(actor, outcome == DeleteOutcome.DELETED ? "DELETE" : "DEACTIVATE", "TAG", code, before,
                referenceData.findTag(code).orElse(null));
        return outcome;
    }

    // ---------------------------------------------------------------- categories

    @Transactional(readOnly = true)
    public List<CategoryRow> categories(UUID actor) {
        roleAccess.require(actor, EDITORS);
        return referenceData.categories();
    }

    @Transactional
    public CategoryRow createCategory(UUID actor, String code, String label, int sortOrder) {
        roleAccess.require(actor, EDITORS);
        CategoryRow created = referenceData.createCategory(code, label, sortOrder);
        auditLog.record(actor, "CREATE", "TAG_CATEGORY", code, null, created);
        return created;
    }

    @Transactional
    public CategoryRow updateCategory(UUID actor, String code, String label, int sortOrder) {
        roleAccess.require(actor, EDITORS);
        CategoryRow before = referenceData.findCategory(code).orElse(null);
        CategoryRow after = referenceData.updateCategory(code, label, sortOrder);
        auditLog.record(actor, "UPDATE", "TAG_CATEGORY", code, before, after);
        return after;
    }

    @Transactional
    public CategoryRow setCategoryScale(UUID actor, String code, boolean scale, String minLabel, String maxLabel,
                                        java.util.Map<String, Integer> positions) {
        roleAccess.require(actor, EDITORS);
        CategoryRow before = referenceData.findCategory(code).orElse(null);
        CategoryRow after = referenceData.setScale(code, scale, minLabel, maxLabel, positions);
        auditLog.record(actor, "SET_SCALE", "TAG_CATEGORY", code, before,
                java.util.Map.of("category", after, "positions", positions));
        return after;
    }

    @Transactional
    public void deleteCategory(UUID actor, String code) {
        roleAccess.require(actor, EDITORS);
        CategoryRow before = referenceData.findCategory(code).orElse(null);
        referenceData.deleteCategory(code);
        auditLog.record(actor, "DELETE", "TAG_CATEGORY", code, before, null);
    }

    // ---------------------------------------------------------------- goals

    @Transactional(readOnly = true)
    public List<GoalRow> goals(UUID actor) {
        roleAccess.require(actor, EDITORS);
        return referenceData.goals();
    }

    @Transactional
    public GoalRow createGoal(UUID actor, String code, GoalFields fields) {
        roleAccess.require(actor, EDITORS);
        GoalRow created = referenceData.createGoal(code, fields);
        auditLog.record(actor, "CREATE", "GOAL", code, null, created);
        return created;
    }

    @Transactional
    public GoalRow updateGoal(UUID actor, String code, GoalFields fields) {
        roleAccess.require(actor, EDITORS);
        GoalRow before = referenceData.requireGoal(code);
        GoalRow after = referenceData.updateGoal(code, fields);
        auditLog.record(actor, "UPDATE", "GOAL", code, before, after);
        return after;
    }

    @Transactional
    public DeleteOutcome deleteGoal(UUID actor, String code) {
        roleAccess.require(actor, EDITORS);
        GoalRow before = referenceData.requireGoal(code);
        DeleteOutcome outcome = referenceData.deleteGoal(code);
        auditLog.record(actor, outcome == DeleteOutcome.DELETED ? "DELETE" : "DEACTIVATE", "GOAL", code, before,
                referenceData.findGoal(code).orElse(null));
        return outcome;
    }
}
