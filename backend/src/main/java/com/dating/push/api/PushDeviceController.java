package com.dating.push.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.push.application.PushService;
import com.dating.push.domain.PushProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Push token of the current device. It is tied to the current session: logout or session expiry stops pushes.
 */
@RestController
@RequestMapping("/api/v1/me/push-device")
public class PushDeviceController {

    public record PushDeviceRequest(
            @NotNull(message = "Укажите провайдера") PushProvider provider,
            @NotBlank(message = "Укажите push-токен") @Size(max = 1024, message = "Слишком длинный токен") String token
    ) {
    }

    private final PushService pushService;

    public PushDeviceController(PushService pushService) {
        this.pushService = pushService;
    }

    @PutMapping
    public ResponseEntity<Void> register(@AuthenticationPrincipal AuthenticatedUser user,
                                         @Valid @RequestBody PushDeviceRequest request) {
        pushService.register(user.accountId(), user.sessionId(), request.provider(), request.token());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> unregister(@AuthenticationPrincipal AuthenticatedUser user) {
        pushService.unregister(user.sessionId());
        return ResponseEntity.noContent().build();
    }
}
