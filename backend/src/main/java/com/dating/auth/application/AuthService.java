package com.dating.auth.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.accounts.application.AccountService;
import com.dating.accounts.domain.Account;
import com.dating.accounts.domain.AccountStatus;
import com.dating.auth.api.AuthChallengeResponseDto;
import com.dating.auth.api.AuthSessionResponseDto;
import com.dating.auth.domain.AuthChallenge;
import com.dating.auth.domain.PhoneNumbers;
import com.dating.auth.domain.Session;
import com.dating.auth.persistence.AuthChallengeRepository;
import com.dating.auth.persistence.SessionRepository;
import com.dating.common.errors.ApiException;
import com.dating.common.ratelimit.FixedWindowRateLimiter;
import com.dating.common.security.AuthenticatedUser;
import com.dating.common.security.SessionValidator;
import com.dating.common.util.Hashing;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService implements SessionValidator {

    /** Fixed code for local development only (mock mode is refused with the prod profile). */
    static final String MOCK_CODE = "123456";
    private static final int CODE_LENGTH = 6;
    private static final Duration HOUR = Duration.ofHours(1);

    private final AuthChallengeRepository challengeRepository;
    private final SessionRepository sessionRepository;
    private final AccountService accountService;
    private final OtpSender otpSender;
    private final AuthProperties properties;
    private final TestAccountPolicy testAccountPolicy;
    private final FixedWindowRateLimiter ipLimiter;
    private final FixedWindowRateLimiter phoneCooldownLimiter;

    public AuthService(AuthChallengeRepository challengeRepository,
                       SessionRepository sessionRepository,
                       AccountService accountService,
                       OtpSender otpSender,
                       AuthProperties properties,
                       TestAccountPolicy testAccountPolicy) {
        this.challengeRepository = challengeRepository;
        this.sessionRepository = sessionRepository;
        this.accountService = accountService;
        this.otpSender = otpSender;
        this.properties = properties;
        this.testAccountPolicy = testAccountPolicy;
        this.ipLimiter = new FixedWindowRateLimiter(properties.maxChallengesPerIpPerHour(), HOUR);
        this.phoneCooldownLimiter = new FixedWindowRateLimiter(1, properties.resendCooldown());
    }

    @Transactional
    public AuthChallengeResponseDto requestOtp(String rawPhone, String clientIp) {
        String phone = PhoneNumbers.normalize(rawPhone).orElseThrow(() -> ApiException.validation(
                "INVALID_PHONE", "Некорректный номер телефона",
                Map.of("phone", "Укажите номер в формате +7 999 123-45-67")));

        long ipWait = ipLimiter.tryAcquire(clientIp == null ? "unknown" : clientIp);
        if (ipWait > 0) {
            throw ApiException.tooManyRequests("Слишком много запросов кода. Попробуйте позже", ipWait);
        }

        String phoneHash = Hashing.sha256Hex(phone);
        Instant now = Instant.now();
        enforcePhoneLimits(phoneHash, now);

        boolean fixedTestCode = testAccountPolicy.usesFixedOtp(phoneHash);
        String code = fixedTestCode ? testAccountPolicy.fixedOtpCode()
                : properties.mockEnabled() ? MOCK_CODE : Hashing.randomDigits(CODE_LENGTH);
        Instant expiresAt = now.plus(properties.otpTtl());
        AuthChallenge challenge = new AuthChallenge(UUID.randomUUID(), phoneHash, PhoneNumbers.mask(phone),
                Hashing.sha256Hex(code), now, expiresAt);
        challengeRepository.save(challenge);

        if (!fixedTestCode) {
            // Throws (and rolls the challenge back) if delivery is impossible.
            otpSender.send(phone, code);
        }

        String devMockCode = properties.mockEnabled() ? code : null;
        return new AuthChallengeResponseDto(challenge.getId(), expiresAt, challenge.getPhoneMasked(), devMockCode);
    }

    private void enforcePhoneLimits(String phoneHash, Instant now) {
        long cooldownWait = phoneCooldownLimiter.tryAcquire(phoneHash);
        if (cooldownWait > 0) {
            throw ApiException.tooManyRequests("Код уже отправлен. Повторить можно чуть позже", cooldownWait);
        }
        // DB checks survive restarts; the in-memory limiter above closes the race between parallel requests.
        challengeRepository.findTopByPhoneHashOrderByCreatedAtDesc(phoneHash).ifPresent(last -> {
            Duration sinceLast = Duration.between(last.getCreatedAt(), now);
            if (sinceLast.compareTo(properties.resendCooldown()) < 0) {
                throw ApiException.tooManyRequests("Код уже отправлен. Повторить можно чуть позже",
                        properties.resendCooldown().minus(sinceLast).toSeconds() + 1);
            }
        });
        long lastHour = challengeRepository.countByPhoneHashAndCreatedAtAfter(phoneHash, now.minus(HOUR));
        if (lastHour >= properties.maxChallengesPerPhonePerHour()) {
            throw ApiException.tooManyRequests("Превышен лимит запросов кода для этого номера. Попробуйте через час",
                    HOUR.toSeconds());
        }
    }

    /**
     * noRollbackFor: a failed attempt must be persisted even though we answer with an error,
     * otherwise the attempt counter is rolled back and the code can be brute-forced.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthSessionResponseDto verifyOtp(UUID challengeId, String code) {
        AuthChallenge challenge = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> ApiException.badRequest("INVALID_CHALLENGE", "Запрос подтверждения не найден"));

        if (challenge.isConsumed()) {
            throw ApiException.badRequest("CHALLENGE_ALREADY_USED", "Код подтверждения уже был использован");
        }
        if (challenge.isExpired()) {
            throw ApiException.badRequest("CHALLENGE_EXPIRED", "Срок действия кода истёк. Запросите новый код");
        }
        if (challenge.getAttemptCount() >= properties.maxAttempts()) {
            throw maxAttemptsExceeded();
        }

        challenge.registerAttempt();
        if (!Hashing.constantTimeEquals(challenge.getCodeHash(), Hashing.sha256Hex(code.trim()))) {
            int attemptsLeft = properties.maxAttempts() - challenge.getAttemptCount();
            if (attemptsLeft <= 0) {
                throw maxAttemptsExceeded();
            }
            throw ApiException.badRequest("INVALID_CODE",
                    "Неверный код подтверждения. Осталось попыток: " + attemptsLeft);
        }
        challenge.markConsumed();

        String phoneMasked = challenge.getPhoneMasked() != null ? challenge.getPhoneMasked() : "+*** ***-**-**";
        Account account = accountService.findOrCreateAccount(challenge.getPhoneHash(), phoneMasked);
        accountService.updateTestAccountFlag(account, testAccountPolicy.isTestPhoneHash(challenge.getPhoneHash()));
        if (!account.isActive()) {
            throw ApiException.forbidden("ACCOUNT_SUSPENDED", "Аккаунт заблокирован");
        }

        String rawToken = Hashing.randomHex(32);
        Instant sessionExpiresAt = Instant.now().plus(properties.sessionTtl());
        sessionRepository.save(new Session(UUID.randomUUID(), account.getId(), Hashing.sha256Hex(rawToken), sessionExpiresAt));

        return new AuthSessionResponseDto(rawToken, sessionExpiresAt, account.getId());
    }

    private ApiException maxAttemptsExceeded() {
        return ApiException.badRequest("MAX_ATTEMPTS_EXCEEDED", "Превышено количество попыток. Запросите новый код");
    }

    @Transactional
    public void logout(UUID sessionId) {
        sessionRepository.findById(sessionId).ifPresent(Session::revoke);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> validateSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.findUsable(Hashing.sha256Hex(rawToken), Instant.now(), AccountStatus.ACTIVE)
                .map(s -> new AuthenticatedUser(s.getAccountId(), s.getId()));
    }

    @EventListener
    @Order(3)
    public void onAccountDeleted(AccountDeletedEvent event) {
        sessionRepository.deleteAllByAccountId(event.accountId());
    }
}
