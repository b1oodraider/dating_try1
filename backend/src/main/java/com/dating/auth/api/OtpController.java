package com.dating.auth.api;

import com.dating.auth.application.AuthService;
import com.dating.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
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
    public ResponseEntity<AuthChallengeResponseDto> requestOtp(@Valid @RequestBody OtpRequestDto request,
                                                               HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.requestOtp(request.phone(), httpRequest.getRemoteAddr()));
    }

    /**
     * The phone is taken from the challenge itself. Accepting it from the client would let anyone
     * verify their own code and log into somebody else's account.
     */
    @PostMapping("/otp/verify")
    public ResponseEntity<AuthSessionResponseDto> verifyOtp(@Valid @RequestBody OtpVerifyDto request) {
        return ResponseEntity.ok(authService.verifyOtp(request.challengeId(), request.code()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser user) {
        authService.logout(user.sessionId());
        return ResponseEntity.noContent().build();
    }
}
