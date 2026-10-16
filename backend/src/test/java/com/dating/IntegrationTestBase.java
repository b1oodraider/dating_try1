package com.dating;

import com.dating.support.TestImages;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Shared setup for integration tests: one PostgreSQL container for the whole test JVM
 * (singleton-container pattern), so every test class reuses the same cached Spring context.
 * Skipped automatically when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTestBase {

    private static final boolean DOCKER = DockerClientFactory.instance().isDockerAvailable();

    /**
     * Same image as production: postgres:16-alpine + Russian Hunspell dictionary (infra/postgres/Dockerfile).
     */
    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(DOCKER ? buildPostgresImage() : "postgres:16-alpine").asCompatibleSubstituteFor("postgres"));

    static {
        if (DOCKER) {
            POSTGRES.start(); // stopped by Testcontainers (Ryuk) when the JVM exits
        }
    }

    private static String buildPostgresImage() {
        Path dockerfile = Path.of("..", "infra", "postgres", "Dockerfile").toAbsolutePath().normalize();
        return new ImageFromDockerfile("dating-postgres-test", false).withDockerfile(dockerfile).get();
    }

    /**
     * Catalog rows used by the economy tests (in addition to the stub catalog seeded by migration 007).
     */
    @BeforeEach
    void testCatalog() {
        jdbc.update("""
                INSERT INTO catalog_items (code, kind, category, equip_slot, title, rarity) VALUES
                  ('TEST_SUPERLIKE', 'CONSUMABLE', 'SUPERLIKE', NULL, 'Test super like', NULL),
                  ('TEST_TICKET', 'CONSUMABLE', 'GACHA_TICKET', NULL, 'Test ticket', NULL),
                  ('TEST_FRAME', 'COSMETIC', 'PHOTO_FRAME', 'PHOTO_FRAME', 'Test frame', 'RARE')
                ON CONFLICT (code) DO NOTHING
                """);
    }

    protected static final Path MEDIA_DIR = createTempDir();
    protected static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    protected static final String MOCK_CODE = "123456";

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("app.media.storage-path", MEDIA_DIR::toString);
        // Both push providers point at a local fake (PushStub), so every context sends real HTTP pushes.
        registry.add("app.push.rustore.base-url", () -> com.dating.support.PushStub.BASE_URL);
        registry.add("app.push.rustore.project-id", () -> com.dating.support.PushStub.RUSTORE_PROJECT);
        registry.add("app.push.rustore.service-token", () -> com.dating.support.PushStub.RUSTORE_SERVICE_TOKEN);
        registry.add("app.push.fcm.base-url", () -> com.dating.support.PushStub.BASE_URL);
        registry.add("app.push.fcm.credentials-file", () -> com.dating.support.PushStub.FCM_CREDENTIALS.toString());
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected com.dating.economy.application.EconomyService economyService;

    /**
     * Gives the account behind the token premium (premium-only features like exclusion words need it).
     */
    protected void grantPremium(String token, int days) throws Exception {
        UUID accountId = UUID.fromString(json(get("/api/v1/me"), token, "$.id"));
        economyService.grant(accountId, com.dating.economy.domain.RewardSource.ADMIN, "test",
                "test-premium:" + UUID.randomUUID(), List.of(new com.dating.economy.domain.Reward.PremiumDays(days)));
    }

    // ------------------------------------------------------------------ helpers

    protected ResultActions putWords(String token, String wordsJson) throws Exception {
        return mvc.perform(put("/api/v1/me/exclusion-words")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"words\":" + wordsJson + "}"));
    }

    protected String publishWith(String token, String name, String city, List<String> tags) throws Exception {
        String tagJson = tags.stream().map(t -> "\"" + t + "\"").reduce((a, b) -> a + "," + b).orElse("");
        putProfile(token, "{\"displayName\":\"" + name + "\",\"age\":30,\"city\":\"" + city + "\",\"tagCodes\":[" + tagJson + "]}")
                .andExpect(status().isOk());
        String photoId = uploadPng(token);
        mvc.perform(post("/api/v1/me/profile/publish").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationStatus").value("PUBLISHED"));
        return photoId;
    }

    protected String uploadPng(String token) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/me/profile/photos")
                        .file(new MockMultipartFile("file", "p.png", "image/png", TestImages.png(3, 3)))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(body(result), "$.id");
    }

    protected ResultActions discovery(String token, String city, String tagCodes, String tagLogic, int limit) throws Exception {
        var request = get("/api/v1/discovery/profiles")
                .header("Authorization", bearer(token))
                .queryParam("city", city)
                .queryParam("limit", String.valueOf(limit));
        if (tagCodes != null) {
            request.queryParam("tagCodes", tagCodes).queryParam("tagLogic", tagLogic);
        }
        return mvc.perform(request).andExpect(status().isOk());
    }

    protected ResultActions putProfile(String token, String json) throws Exception {
        return mvc.perform(put("/api/v1/me/profile")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(json));
    }

    protected String login(String phone) throws Exception {
        MvcResult result = verify(requestOtp(phone), MOCK_CODE).andExpect(status().isOk()).andReturn();
        return JsonPath.read(body(result), "$.token");
    }

    protected String requestOtp(String phone) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(phoneBody(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devMockCode").value(MOCK_CODE))
                .andReturn();
        return JsonPath.read(body(result), "$.challengeId");
    }

    protected ResultActions verify(String challengeId, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyBody(challengeId, code)));
    }

    protected String json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                        String token, String path) throws Exception {
        MvcResult result = mvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(body(result), path);
    }

    protected static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static String phoneBody(String phone) {
        return "{\"phone\":\"" + phone + "\"}";
    }

    protected static String verifyBody(String challengeId, String code) {
        return "{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + code + "\"}";
    }

    protected static String newPhoneDigits() {
        return String.format("9%09d", PHONE_SEQ.incrementAndGet() % 1_000_000_000L);
    }

    protected static String newPhone() {
        return "+7" + newPhoneDigits();
    }

    protected static String uniqueCity() {
        return "Тестоград-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static Path createTempDir() {
        try {
            return Files.createTempDirectory("dating-media-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
