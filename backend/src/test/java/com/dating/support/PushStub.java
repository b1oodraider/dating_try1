package com.dating.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Fake RuStore Push + FCM (incl. the Google OAuth token endpoint) on one local port, shared by the test JVM.
 * Records every accepted push; tokens in {@link #DEAD_TOKENS} get the provider's "token is gone" answer.
 */
public final class PushStub {

    public record Push(String provider, String authorization, String body) {
    }

    public static final String RUSTORE_PROJECT = "rustore-test-project";
    public static final String RUSTORE_SERVICE_TOKEN = "rustore-service-token";
    public static final String FCM_PROJECT = "fcm-test-project";
    public static final Set<String> DEAD_TOKENS = ConcurrentHashMap.newKeySet();

    private static final BlockingQueue<Push> PUSHES = new LinkedBlockingQueue<>();
    private static final KeyPair FCM_KEY = rsaKey();
    private static final HttpServer SERVER = start();
    public static final String BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();
    public static final Path FCM_CREDENTIALS = writeServiceAccount();

    private PushStub() {
    }

    /** Next push for the token, or null after the timeout (deliveries are asynchronous). */
    public static Push next(String token, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            Push push = left <= 0 ? null : PUSHES.poll(left, TimeUnit.MILLISECONDS);
            if (push == null || push.body().contains("\"" + token + "\"")) {
                return push;
            }
            PUSHES.add(push); // someone else's push: keep it for another test
            Thread.sleep(10);
        }
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean valid = form.contains("jwt-bearer") && validJwt(form.substring(form.indexOf("assertion=") + 10));
                respond(exchange, valid ? 200 : 400, valid
                        ? "{\"access_token\": \"fcm-access-token\", \"expires_in\": 3600, \"token_type\": \"Bearer\"}"
                        : "{\"error\": \"invalid_grant\"}");
            });
            server.createContext("/v1/projects/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String provider = path.contains(RUSTORE_PROJECT) ? "RUSTORE" : path.contains(FCM_PROJECT) ? "FCM" : null;
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (provider == null) {
                    respond(exchange, 404, "{}");
                    return;
                }
                if (DEAD_TOKENS.stream().anyMatch(t -> body.contains("\"" + t + "\""))) {
                    respond(exchange, 404, "{\"error\": {\"code\": 404, \"status\": \"NOT_FOUND\"}}");
                    return;
                }
                PUSHES.add(new Push(provider, auth, body));
                respond(exchange, 200, "{}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean validJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(FCM_KEY.getPublic());
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception e) {
            return false;
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static KeyPair rsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeServiceAccount() {
        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(FCM_KEY.getPrivate().getEncoded()) + "\\n-----END PRIVATE KEY-----\\n";
        String json = """
                {"type": "service_account", "project_id": "%s", "client_email": "push@%s.iam.example",
                 "private_key": "%s", "token_uri": "%s/token"}""".formatted(FCM_PROJECT, FCM_PROJECT, pem, BASE_URL);
        try {
            Path file = Files.createTempFile("fcm-service-account", ".json");
            Files.writeString(file, json);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
