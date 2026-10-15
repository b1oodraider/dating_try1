package com.dating;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers for the admin API tests. The test profile bootstraps +7 801 000-00-01 as ADMIN.
 * Its token is cached for the whole JVM: logging in twice within the resend cooldown is rate limited.
 */
abstract class AdminTestSupport extends IntegrationTestBase {

    protected static final String ADMIN_PHONE = "+7 801 000-00-01";
    private static String adminToken;

    protected synchronized String adminToken() throws Exception {
        if (adminToken == null) {
            adminToken = login(ADMIN_PHONE);
        }
        return adminToken;
    }

    /**
     * A fresh real account that the admin made CONTENT_EDITOR.
     */
    protected String editorToken() throws Exception {
        String token = login(newPhone());
        admin(put("/api/v1/admin/accounts/" + accountId(token) + "/roles/CONTENT_EDITOR"), null)
                .andExpect(status().isOk());
        return token;
    }

    protected String accountId(String token) throws Exception {
        return json(get("/api/v1/me"), token, "$.id");
    }

    protected ResultActions admin(MockHttpServletRequestBuilder request, String json) throws Exception {
        return as(adminToken(), request, json);
    }

    protected ResultActions as(String token, MockHttpServletRequestBuilder request, String json) throws Exception {
        request.header("Authorization", bearer(token));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).characterEncoding(StandardCharsets.UTF_8).content(json);
        }
        return mvc.perform(request);
    }

    /**
     * Upper-case suffix for unique codes (the DB is shared by all tests of the run).
     */
    protected static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
