package com.dating.discovery.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.discovery.application.ExcludedGoalsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me/excluded-goals")
public class ExcludedGoalsController {

    public record UpdateRequest(
            @NotNull(message = "Передайте список целей (может быть пустым)")
            @Size(max = 20, message = "Слишком длинный список")
            List<String> goalCodes) {
    }

    private final ExcludedGoalsService service;

    public ExcludedGoalsController(ExcludedGoalsService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ExcludedGoalsDto> get(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(new ExcludedGoalsDto(service.goalsOf(user.accountId()), service.isActive(user.accountId())));
    }

    @PutMapping
    public ResponseEntity<ExcludedGoalsDto> replace(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @Valid @RequestBody UpdateRequest request) {
        return ResponseEntity.ok(new ExcludedGoalsDto(service.replace(user.accountId(), request.goalCodes()),
                service.isActive(user.accountId())));
    }
}
