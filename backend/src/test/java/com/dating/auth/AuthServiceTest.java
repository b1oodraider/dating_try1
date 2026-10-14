package com.dating.auth;

import com.dating.accounts.application.AccountService;
import com.dating.accounts.domain.Account;
import com.dating.auth.api.AuthChallengeResponseDto;
import com.dating.auth.api.AuthSessionResponseDto;
import com.dating.auth.application.AuthService;
import com.dating.auth.domain.AuthChallenge;
import com.dating.auth.domain.Session;
import com.dating.auth.persistence.AuthChallengeRepository;
import com.dating.auth.persistence.SessionRepository;
import com.dating.common.errors.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthChallengeRepository challengeRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private AccountService accountService;

    private AuthService authServiceDev;
    private AuthService authServiceProd;

    @BeforeEach
    void setUp() {
        authServiceDev = new AuthService(challengeRepository, sessionRepository, accountService, true);
        authServiceProd = new AuthService(challengeRepository, sessionRepository, accountService, false);
    }

    @Test
    @DisplayName("Dev mode requests OTP with mock code, masked phone")
    void testRequestOtp_DevMode() {
        when(challengeRepository.save(any(AuthChallenge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthChallengeResponseDto response = authServiceDev.requestOtp("+79991234567");

        assertNotNull(response.challengeId());
        assertEquals("123456", response.devMockCode());
        assertTrue(response.maskedPhone().contains("+7 (***) ***-**-67"));
        verify(challengeRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Prod mode must never return mock code")
    void testRequestOtp_ProdMode_NoMockCode() {
        when(challengeRepository.save(any(AuthChallenge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthChallengeResponseDto response = authServiceProd.requestOtp("+79991234567");

        assertNull(response.devMockCode(), "In production, devMockCode must strictly be null");
    }

    @Test
    @DisplayName("Verify OTP with valid code creates session")
    void testVerifyOtp_Success() throws Exception {
        UUID challengeId = UUID.randomUUID();
        String code = "123456";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String codeHash = HexFormat.of().formatHex(md.digest(code.getBytes(StandardCharsets.UTF_8)));

        AuthChallenge challenge = new AuthChallenge(challengeId, "phoneHash", codeHash, Instant.now().plus(5, ChronoUnit.MINUTES));
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(challenge));

        Account account = new Account(UUID.randomUUID(), "phoneHash", "+7 (***) ***-**-67");
        when(accountService.findOrCreateAccount(anyString(), anyString())).thenReturn(account);
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthSessionResponseDto session = authServiceDev.verifyOtp(challengeId, code, "+79991234567");

        assertNotNull(session.token());
        assertEquals(account.getId(), session.accountId());
        assertTrue(challenge.isConsumed());
    }

    @Test
    @DisplayName("Verify OTP with wrong code rejects and increments attempt count")
    void testVerifyOtp_WrongCode_Fails() throws Exception {
        UUID challengeId = UUID.randomUUID();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String codeHash = HexFormat.of().formatHex(md.digest("123456".getBytes(StandardCharsets.UTF_8)));

        AuthChallenge challenge = new AuthChallenge(challengeId, "phoneHash", codeHash, Instant.now().plus(5, ChronoUnit.MINUTES));
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(challenge));

        assertThrows(ApiException.class, () -> authServiceDev.verifyOtp(challengeId, "000000", "+79991234567"));
        assertEquals(1, challenge.getAttemptCount());
        assertFalse(challenge.isConsumed());
    }
}
