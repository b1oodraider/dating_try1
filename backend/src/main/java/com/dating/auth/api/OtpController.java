package com.dating.auth.api;

import com.dating.auth.application.AuthService;
import com.dating.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class OtpController {

    private final AuthService authService;

    public OtpController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/otp/request")
    public ResponseEntity<AuthChallengeResponseDto> requestOtp(@Valid @RequestBody OtpRequestDto request) {
        AuthChallengeResponseDto response = authService.requestOtp(request.phone());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<AuthSessionResponseDto> verifyOtp(@Valid @RequestBody OtpVerifyDto request,
                                                           @RequestParam(name = "phone") String phone) {
        AuthSessionResponseDto response = authService.verifyOtp(request.challengeId(), request.code(), phone);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user != null) {
            authService.logout(user.sessionId());
        }
        return ResponseEntity.noContent().build();
    }
}
