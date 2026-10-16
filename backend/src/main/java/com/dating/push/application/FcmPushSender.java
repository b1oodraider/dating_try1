package com.dating.push.application;

import com.dating.push.domain.PushProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * FCM HTTP v1 API (Android and, through APNs, iOS). Authenticated with an OAuth2 access token obtained from
 * the service account key (JWT bearer grant, RS256), cached until shortly before it expires.
 * 404 (UNREGISTERED) or a 400 about the registration token means the token is dead.
 */
@Component
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final PushProperties.Fcm properties;
    private final JsonMapper jsonMapper;

    private volatile ServiceAccount account;
    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    record ServiceAccount(String projectId, String clientEmail, PrivateKey privateKey, String tokenUri) {
    }

    public FcmPushSender(PushProperties properties, JsonMapper jsonMapper) {
        this.properties = properties.fcm();
        this.jsonMapper = jsonMapper;
    }

    @Override
    public PushProvider provider() {
        return PushProvider.FCM;
    }

    @Override
    public Result send(String token, PushMessage message) {
        if (!properties.configured()) {
            return Result.FAILED;
        }
        String bearer = accessToken();
        if (bearer == null) {
            return Result.FAILED;
        }
        String url = properties.baseUrl() + "/v1/projects/" + account.projectId() + "/messages:send";
        HttpResponse<String> response = PushHttp.post(url, "application/json", bearer,
                jsonMapper.writeValueAsString(message.v1Body(token)));
        if (response == null) {
            return Result.FAILED;
        }
        int status = response.statusCode();
        if (status == 200) {
            return Result.SENT;
        }
        if (status == 404 || (status == 400 && response.body().contains("registration token"))) {
            return Result.INVALID_TOKEN;
        }
        if (status == 401) {
            invalidateAccessToken();
        }
        log.warn("FCM push failed: HTTP {} {}", status, response.body());
        return Result.FAILED;
    }

    private synchronized void invalidateAccessToken() {
        accessToken = null;
    }

    private synchronized String accessToken() {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiresAt)) {
            return accessToken;
        }
        try {
            if (account == null) {
                account = loadServiceAccount(Path.of(properties.credentialsFile()));
            }
            Instant now = Instant.now();
            String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                    + "&assertion=" + signedJwt(now);
            HttpResponse<String> response = PushHttp.post(account.tokenUri(), "application/x-www-form-urlencoded", null, form);
            if (response == null || response.statusCode() != 200) {
                log.warn("FCM OAuth token request failed: {}", response == null ? "network error" : response.body());
                return null;
            }
            Map<?, ?> body = jsonMapper.readValue(response.body(), Map.class);
            accessToken = (String) body.get("access_token");
            long expiresIn = ((Number) body.get("expires_in")).longValue();
            accessTokenExpiresAt = now.plusSeconds(Math.max(0, expiresIn - 60));
            return accessToken;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            log.error("FCM is configured but the service account cannot be used: {}", e.getMessage());
            return null;
        }
    }

    private String signedJwt(Instant now) throws GeneralSecurityException {
        String header = b64(jsonMapper.writeValueAsString(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = b64(jsonMapper.writeValueAsString(Map.of(
                "iss", account.clientEmail(),
                "scope", SCOPE,
                "aud", account.tokenUri(),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(3600).getEpochSecond())));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(account.privateKey());
        signature.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + B64URL.encodeToString(signature.sign());
    }

    private ServiceAccount loadServiceAccount(Path file) throws IOException, GeneralSecurityException {
        Map<?, ?> json = jsonMapper.readValue(Files.readString(file), Map.class);
        String pem = ((String) json.get("private_key"))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Object tokenUri = json.get("token_uri");
        return new ServiceAccount((String) json.get("project_id"), (String) json.get("client_email"), key,
                tokenUri == null ? "https://oauth2.googleapis.com/token" : (String) tokenUri);
    }

    private static String b64(String json) {
        return B64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
