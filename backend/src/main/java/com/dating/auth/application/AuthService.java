package com.dating.auth.application;

import com.dating.accounts.application.AccountService;
import com.dating.accounts.domain.Account;
import com.dating.auth.api.AuthChallengeResponseDto;
import com.dating.auth.api.AuthSessionResponseDto;
import com.dating.auth.domain.AuthChallenge;
import com.dating.auth.domain.Session;
import com.dating.auth.persistence.AuthChallengeRepository;
import com.dating.auth.persistence.SessionRepository;
import com.dating.common.errors.ApiException;
import com.dating.common.security.AuthenticatedUser;
import com.dating.common.security.SessionValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService implements SessionValidator {

    private final AuthChallengeRepository challengeRepository;
    private final SessionRepository sessionRepository;
    private final AccountService accountService;
    private final boolean mockEnabled;

    public AuthService(
            AuthChallengeRepository challengeRepository,
            SessionRepository sessionRepository,
            AccountService accountService,
            @Value("${app.auth.mock-enabled:false}") boolean mockEnabled
    ) {
        this.challengeRepository = challengeRepository;
        this.sessionRepository = sessionRepository;
        this.accountService = accountService;
        this.mockEnabled = mockEnabled;
    }

    @Transactional
    public AuthChallengeResponseDto requestOtp(String phone) {
        String phoneHash = sha256(phone.trim());
        String masked = maskPhone(phone);

        String code = mockEnabled ? "123456" : generateRandomCode();
        String codeHash = sha256(code);

        Instant expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES);
        AuthChallenge challenge = new AuthChallenge(UUID.randomUUID(), phoneHash, codeHash, expiresAt);
        challengeRepository.save(challenge);

        // devMockCode is strictly null in production (when mockEnabled is false)
        String devMock = mockEnabled ? code : null;
        return new AuthChallengeResponseDto(challenge.getId(), expiresAt, masked, devMock);
    }

    @Transactional
    public AuthSessionResponseDto verifyOtp(UUID challengeId, String code, String phone) {
        AuthChallenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> ApiException.badRequest("INVALID_CHALLENGE", "Запрос подтверждения не найден"));

        if (challenge.isConsumed()) {
            throw ApiException.badRequest("CHALLENGE_ALREADY_USED", "Код подтверждения уже был использован");
        }

        if (challenge.isExpired()) {
            throw ApiException.badRequest("CHALLENGE_EXPIRED", "Срок действия кода истёк");
        }

        challenge.incrementAttempt();
        if (challenge.getAttemptCount() > 5) {
            throw ApiException.badRequest("MAX_ATTEMPTS_EXCEEDED", "Превышено количество попыток ввода кода");
        }

        String inputHash = sha256(code.trim());
        if (!challenge.getCodeHash().equals(inputHash)) {
            challengeRepository.save(challenge);
            throw ApiException.badRequest("INVALID_CODE", "Неверный код подтверждения");
        }

        challenge.markConsumed();
        challengeRepository.save(challenge);

        String phoneHash = sha256(phone.trim());
        String phoneMasked = maskPhone(phone);
        Account account = accountService.findOrCreateAccount(phoneHash, phoneMasked);

        // Generate cryptographically secure session token
        String rawToken = generateSecureToken();
        String tokenHash = sha256(rawToken);

        Instant sessionExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
        Session session = new Session(UUID.randomUUID(), account.getId(), tokenHash, sessionExpiresAt);
        sessionRepository.save(session);

        return new AuthSessionResponseDto(rawToken, sessionExpiresAt, account.getId());
    }

    @Transactional
    public void logout(UUID sessionId) {
        sessionRepository.findById(sessionId).ifPresent(Session::revoke);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> validateSession(String rawToken) {
        String tokenHash = sha256(rawToken);
        return sessionRepository.findByTokenHash(tokenHash)
                .filter(Session::isValid)
                .map(s -> new AuthenticatedUser(s.getAccountId(), s.getId()));
    }

    private String maskPhone(String phone) {
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 4) return "+7 (***) ***-**-**";
        String last2 = digits.substring(digits.length() - 2);
        return "+7 (***) ***-**-" + last2;
    }

    private String generateRandomCode() {
        SecureRandom random = new SecureRandom();
        int num = 100000 + random.nextInt(900000);
        return String.valueOf(num);
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
