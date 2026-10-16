package com.dating.social.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.social.application.ReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports for moderation (stored with status NEW, see /api/v1/admin/reports).
 */
@RestController
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/api/v1/reports")
    public ResponseEntity<ReportResponseDto> report(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @Valid @RequestBody ReportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reportService.report(user.accountId(), request));
    }
}
