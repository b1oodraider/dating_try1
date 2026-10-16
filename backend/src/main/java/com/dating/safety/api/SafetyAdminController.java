package com.dating.safety.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.safety.application.BehaviorService;
import com.dating.safety.application.SafetyAdminService;
import com.dating.safety.domain.Restriction;
import com.dating.safety.domain.RestrictionKind;
import com.dating.safety.domain.SanctionPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Sanctions in the back office. Moderators (MODERATOR or ADMIN) see an account's restrictions and add manual
 * ones; lifting a restriction and editing the sanction policy is ADMIN only. Everything is audited.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class SafetyAdminController {

    /** @param hours null = until lifted */
    public record RestrictRequest(
            @NotNull(message = "Укажите вид ограничения") RestrictionKind kind,
            @Min(value = 1, message = "От 1 часа") @Max(value = 87600, message = "Не больше 10 лет") Integer hours,
            @Size(max = 1000, message = "Заметка не должна превышать 1000 символов") String note
    ) {
    }

    public record LiftRequest(@Size(max = 1000, message = "Заметка не должна превышать 1000 символов") String note) {
    }

    public record PolicyRequest(
            @Min(value = 1, message = "От 1 дня") @Max(value = 3650, message = "Не больше 3650 дней") int windowDays,
            @Min(value = 1, message = "От 1") @Max(value = 100, message = "Не больше 100") Integer holdOnReports,
            @NotNull(message = "Укажите шаги") @Size(min = 1, max = 20, message = "От 1 до 20 шагов")
            List<@Valid StepRequest> steps
    ) {
    }

    public record StepRequest(
            @NotNull(message = "Укажите ограничения шага") @Size(max = 5, message = "Не больше 5 ограничений")
            List<@Valid StepRestrictionRequest> restrictions
    ) {
    }

    public record StepRestrictionRequest(
            @NotNull(message = "Укажите вид ограничения") RestrictionKind kind,
            @Min(value = 1, message = "От 1 часа") @Max(value = 87600, message = "Не больше 10 лет") Integer hours
    ) {
    }

    private final SafetyAdminService service;
    private final BehaviorService behaviorService;

    public SafetyAdminController(SafetyAdminService service, BehaviorService behaviorService) {
        this.service = service;
        this.behaviorService = behaviorService;
    }

    /** Behavioral metrics of the account, recomputed now (moderators). */
    @GetMapping("/accounts/{accountId}/behavior")
    public BehaviorService.Metrics behavior(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID accountId) {
        return behaviorService.metricsFor(user.accountId(), accountId);
    }

    @GetMapping("/accounts/{accountId}/restrictions")
    public List<Restriction> history(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID accountId) {
        return service.history(user.accountId(), accountId);
    }

    @PostMapping("/accounts/{accountId}/restrictions")
    public ResponseEntity<Restriction> restrict(@AuthenticationPrincipal AuthenticatedUser user,
                                                @PathVariable UUID accountId,
                                                @Valid @RequestBody RestrictRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.restrict(user.accountId(), accountId, request));
    }

    @PostMapping("/restrictions/{restrictionId}/lift")
    public Restriction lift(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID restrictionId,
                            @Valid @RequestBody(required = false) LiftRequest request) {
        return service.lift(user.accountId(), restrictionId, request == null ? null : request.note());
    }

    @GetMapping("/sanction-policy")
    public List<SanctionPolicy> policies(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.policies(user.accountId());
    }

    @PutMapping("/sanction-policy/{reason}")
    public SanctionPolicy updatePolicy(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reason,
                                       @Valid @RequestBody PolicyRequest request) {
        return service.updatePolicy(user.accountId(), reason, request);
    }
}
