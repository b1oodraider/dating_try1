package com.dating;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Staff roles: bootstrap by phone, grant/revoke by ADMIN (audited), role checks read from the DB,
 * and the testing endpoints that let a test account give itself roles.
 */
class AdminRolesIntegrationTest extends AdminTestSupport {

    @Test
    void bootstrapPhoneBecomesAdminOnLoginOthersHaveNoRoles() throws Exception {
        as(adminToken(), get("/api/v1/me"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("ADMIN")));
        as(login(newPhone()), get("/api/v1/me"), null)
                .andExpect(jsonPath("$.roles", hasSize(0)));
    }

    @Test
    void adminEndpointsNeedAuthenticationAndRoles() throws Exception {
        mvc.perform(get("/api/v1/admin/tags")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/roles")).andExpect(status().isUnauthorized());

        String user = login(newPhone());
        as(user, get("/api/v1/admin/tags"), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_REQUIRED"))
                .andExpect(jsonPath("$.details.roles", containsInAnyOrder("CONTENT_EDITOR", "ADMIN")));
        as(user, get("/api/v1/admin/roles"), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details.roles", contains("ADMIN")));
        as(user, put("/api/v1/admin/accounts/" + accountId(user) + "/roles/ADMIN"), null)
                .andExpect(status().isForbidden());

        // An editor manages content but not roles, authors, premium or the audit log.
        String editor = editorToken();
        as(editor, get("/api/v1/admin/tags"), null).andExpect(status().isOk());
        as(editor, get("/api/v1/admin/roles"), null).andExpect(status().isForbidden());
        as(editor, get("/api/v1/admin/premium"), null).andExpect(status().isForbidden());
        as(editor, get("/api/v1/admin/audit"), null).andExpect(status().isForbidden());
        as(editor, post("/api/v1/admin/authors"), "{\"displayName\": \"Автор\"}").andExpect(status().isForbidden());

        // MODERATOR has no content rights yet.
        String moderator = login(newPhone());
        admin(put("/api/v1/admin/accounts/" + accountId(moderator) + "/roles/MODERATOR"), null).andExpect(status().isOk());
        as(moderator, get("/api/v1/admin/tags"), null).andExpect(status().isForbidden());
    }

    @Test
    void adminGrantsAndRevokesRolesWithImmediateEffectAndAudit() throws Exception {
        String digits = newPhoneDigits();
        String user = login("+7" + digits);
        String userId = accountId(user);
        String rolePath = "/api/v1/admin/accounts/" + userId + "/roles/";

        // Phones are hashed: lookup works with any spelling of the number.
        admin(get("/api/v1/admin/accounts/by-phone").queryParam("phone", "8" + digits), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(userId))
                .andExpect(jsonPath("$.phoneMasked").value(endsWith(digits.substring(8))))
                .andExpect(jsonPath("$.roles", hasSize(0)));
        admin(get("/api/v1/admin/accounts/by-phone").queryParam("phone", "+7 999 000-00-00"), null)
                .andExpect(status().isNotFound());
        admin(get("/api/v1/admin/accounts/by-phone").queryParam("phone", "abc"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHONE"));

        admin(put(rolePath + "CONTENT_EDITOR"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("CONTENT_EDITOR")));
        admin(put(rolePath + "content_editor"), null) // idempotent, case-insensitive
                .andExpect(jsonPath("$.roles", contains("CONTENT_EDITOR")));
        as(user, get("/api/v1/me"), null).andExpect(jsonPath("$.roles", contains("CONTENT_EDITOR")));
        as(user, get("/api/v1/admin/tags"), null).andExpect(status().isOk());

        admin(get("/api/v1/admin/roles"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available", contains("ADMIN", "CONTENT_EDITOR", "MODERATOR")))
                .andExpect(jsonPath("$.accounts[?(@.accountId == '" + userId + "')].roles[0]", contains("CONTENT_EDITOR")));
        admin(get("/api/v1/admin/accounts/" + userId), null).andExpect(jsonPath("$.roles", contains("CONTENT_EDITOR")));

        admin(put(rolePath + "SUPERUSER"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_ROLE"));
        admin(put("/api/v1/admin/accounts/" + UUID.randomUUID() + "/roles/ADMIN"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        // Same token, no re-login: the role is gone on the next request.
        admin(delete(rolePath + "CONTENT_EDITOR"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(0)));
        as(user, get("/api/v1/admin/tags"), null).andExpect(status().isForbidden());

        String adminId = accountId(adminToken());
        admin(get("/api/v1/admin/audit").queryParam("entityType", "ACCOUNT_ROLE").queryParam("entityId", userId), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", contains("REVOKE", "GRANT")))
                .andExpect(jsonPath("$[0].actorAccountId").value(adminId))
                .andExpect(jsonPath("$[0].before", contains("CONTENT_EDITOR")))
                .andExpect(jsonPath("$[0].after", hasSize(0)));
    }

    @Test
    void secondAdminCanManageRolesAndDeletedAccountsLoseThem() throws Exception {
        String second = login(newPhone());
        String secondId = accountId(second);
        admin(put("/api/v1/admin/accounts/" + secondId + "/roles/ADMIN"), null).andExpect(status().isOk());
        as(second, get("/api/v1/admin/roles"), null).andExpect(status().isOk());

        String other = login(newPhone());
        as(second, put("/api/v1/admin/accounts/" + accountId(other) + "/roles/MODERATOR"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("MODERATOR")));

        as(second, delete("/api/v1/me"), null).andExpect(status().isNoContent());
        admin(get("/api/v1/admin/accounts/" + secondId), null)
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.roles", hasSize(0)));
        admin(put("/api/v1/admin/accounts/" + secondId + "/roles/ADMIN"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    void testAccountsGiveThemselvesRolesViaTestingApi() throws Exception {
        String tester = testLogin("+78000000007");
        as(tester, post("/api/v1/testing/roles/CONTENT_EDITOR"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testAccount").value(true))
                .andExpect(jsonPath("$.roles", contains("CONTENT_EDITOR")));
        as(tester, get("/api/v1/admin/tags"), null).andExpect(status().isOk());
        as(tester, post("/api/v1/testing/roles/ADMIN"), null)
                .andExpect(jsonPath("$.roles", contains("ADMIN", "CONTENT_EDITOR")));
        as(tester, get("/api/v1/admin/roles"), null).andExpect(status().isOk());

        as(tester, delete("/api/v1/testing/roles/ADMIN"), null).andExpect(jsonPath("$.roles", contains("CONTENT_EDITOR")));
        as(tester, delete("/api/v1/testing/roles/CONTENT_EDITOR"), null).andExpect(jsonPath("$.roles", hasSize(0)));
        as(tester, get("/api/v1/admin/tags"), null).andExpect(status().isForbidden());
        as(tester, post("/api/v1/testing/roles/KING"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_ROLE"));

        admin(get("/api/v1/admin/audit").queryParam("entityId", accountId(tester)), null)
                .andExpect(jsonPath("$[0].action").value("TESTING_REVOKE"))
                .andExpect(jsonPath("$[*].action", hasItem("TESTING_GRANT")));

        as(login(newPhone()), post("/api/v1/testing/roles/ADMIN"), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEST_ACCOUNT_REQUIRED"));
    }

    private String testLogin(String phone) throws Exception {
        MvcResult challenge = mvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(phoneBody(phone)))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult session = verify(JsonPath.read(body(challenge), "$.challengeId"), "654321")
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(body(session), "$.token");
    }
}
