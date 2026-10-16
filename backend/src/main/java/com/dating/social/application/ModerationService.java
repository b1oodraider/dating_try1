package com.dating.social.application;

import com.dating.admin.application.AdminAuditLog;
import com.dating.common.errors.ApiException;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.media.application.MediaService;
import com.dating.profiles.application.ProfileService;
import com.dating.safety.application.SanctionService;
import com.dating.social.api.ModerationPageDto;
import com.dating.social.api.ModerationReportDto;
import com.dating.social.api.ReportDecisionRequest;
import com.dating.social.domain.ReportDecision;
import com.dating.social.domain.ReportReason;
import com.dating.social.persistence.ModerationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Moderator queue for reports: a report is ACCEPTED (violation as reported), RECLASSIFIED (a violation of
 * another kind) or REJECTED. A confirmed violation gets the sanction of its final reason (sanction_policy,
 * escalating with repeats) and may remove the offending photos / texts.
 */
@Service
public class ModerationService {

    static final int MAX_LIMIT = 100;
    private static final Set<String> STATUSES = Set.of("NEW", "IN_REVIEW", "RESOLVED", "DISMISSED");
    private static final List<String> OPEN = List.of("NEW", "IN_REVIEW");

    private final ModerationRepository repository;
    private final RoleAccess roleAccess;
    private final AdminAuditLog auditLog;
    private final SanctionService sanctionService;
    private final MediaService mediaService;
    private final ProfileService profileService;

    public ModerationService(ModerationRepository repository, RoleAccess roleAccess, AdminAuditLog auditLog,
                             SanctionService sanctionService, MediaService mediaService, ProfileService profileService) {
        this.repository = repository;
        this.roleAccess = roleAccess;
        this.auditLog = auditLog;
        this.sanctionService = sanctionService;
        this.mediaService = mediaService;
        this.profileService = profileService;
    }

    /**
     * @param status null = open reports (NEW, IN_REVIEW)
     * @throws ApiException 400 VALIDATION_ERROR for an unknown status
     */
    @Transactional(readOnly = true)
    public ModerationPageDto queue(UUID actor, String status, int page, int limit) {
        requireModerator(actor);
        List<String> statuses = status == null ? OPEN : List.of(parseStatus(status));
        int safeLimit = limit <= 0 || limit > MAX_LIMIT ? 20 : limit;
        int safePage = Math.max(page, 0);
        return new ModerationPageDto(repository.page(statuses, safeLimit, safePage * safeLimit),
                safePage, safeLimit, repository.count(statuses));
    }

    @Transactional(readOnly = true)
    public ModerationReportDto get(UUID actor, UUID reportId) {
        requireModerator(actor);
        return find(reportId);
    }

    /**
     * @throws ApiException 404 REPORT_NOT_FOUND, 409 REPORT_ALREADY_DECIDED, 404 PHOTO_NOT_FOUND (not a photo of
     *                      the reported profile), 400 VALIDATION_ERROR (reason missing/unchanged for RECLASSIFIED,
     *                      given otherwise; content removal with REJECTED)
     */
    @Transactional
    public ModerationReportDto decide(UUID actor, UUID reportId, ReportDecisionRequest request) {
        requireModerator(actor);
        String previous = repository.lockDecision(reportId).orElseThrow(ModerationService::notFound);
        if (!previous.isEmpty()) {
            throw ApiException.conflict("REPORT_ALREADY_DECIDED", "По жалобе уже принято решение");
        }
        ModerationReportDto before = find(reportId);
        ReportReason finalReason = finalReason(request, before.reason());
        String status = request.decision() == ReportDecision.REJECTED ? "DISMISSED" : "RESOLVED";
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (request.decision() == ReportDecision.REJECTED && request.removesContent()) {
            throw ApiException.validation("VALIDATION_ERROR", "Отклонённая жалоба не удаляет контент",
                    Map.of("decision", "Контент удаляют только при подтверждённом нарушении"));
        }
        repository.decide(reportId, request.decision(), status, finalReason, actor, note, Instant.now());

        UUID reported = before.reportedAccountId();
        if (finalReason != null) {
            if (request.removePhotoIds() != null) {
                request.removePhotoIds().forEach(photoId -> mediaService.deletePhoto(reported, photoId));
            }
            if (request.clearsAboutText() || request.clearsDailyStatus()) {
                profileService.clearTextsByModeration(reported, request.clearsAboutText(), request.clearsDailyStatus());
            }
            if (request.applySanction()) {
                sanctionService.applyReportSanction(reported, reportId, finalReason.name(), actor);
            }
        }
        sanctionService.syncReviewHold(reported);
        ModerationReportDto after = find(reportId);
        auditLog.record(actor, request.decision().name(), "REPORT", reportId.toString(), before, after);
        return after;
    }

    private static ReportReason finalReason(ReportDecisionRequest request, ReportReason reported) {
        return switch (request.decision()) {
            case ACCEPTED -> {
                rejectReason(request);
                yield reported;
            }
            case RECLASSIFIED -> {
                if (request.reason() == null || request.reason() == reported) {
                    throw ApiException.validation("VALIDATION_ERROR", "Укажите другую причину нарушения",
                            Map.of("reason", "Должна отличаться от причины в жалобе"));
                }
                yield request.reason();
            }
            case REJECTED -> {
                rejectReason(request);
                yield null;
            }
        };
    }

    private static void rejectReason(ReportDecisionRequest request) {
        if (request.reason() != null) {
            throw ApiException.validation("VALIDATION_ERROR", "Причину меняют только при переквалификации",
                    Map.of("reason", "Укажите только для RECLASSIFIED"));
        }
    }

    private void requireModerator(UUID actor) {
        roleAccess.require(actor, Role.MODERATOR, Role.ADMIN);
    }

    private ModerationReportDto find(UUID reportId) {
        return repository.find(reportId).orElseThrow(ModerationService::notFound);
    }

    private static String parseStatus(String status) {
        String upper = status.toUpperCase();
        if (!STATUSES.contains(upper)) {
            throw ApiException.validation("VALIDATION_ERROR", "Неизвестный статус жалобы",
                    Map.of("status", "Одно из: NEW, IN_REVIEW, RESOLVED, DISMISSED"));
        }
        return upper;
    }

    private static ApiException notFound() {
        return ApiException.notFound("REPORT_NOT_FOUND", "Жалоба не найдена");
    }
}
