package com.dating;

import com.dating.support.PushStub;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Support tickets: the user's thread, the staff queue (SUPPORT / MODERATOR / ADMIN), push on answer, limits. */
class SupportIntegrationTest extends AdminTestSupport {

    private static final String TICKETS = "/api/v1/support/tickets";
    private static final String STAFF = "/api/v1/admin/support/tickets";

    @Test
    void userOpensTicketStaffAnswersUserReopensStaffCloses() throws Exception {
        String user = login(newPhone());
        String device = "fcm-" + UUID.randomUUID();
        as(user, put("/api/v1/me/push-device"), "{\"provider\": \"FCM\", \"token\": \"" + device + "\"}")
                .andExpect(status().isNoContent());
        String support = staff("SUPPORT");

        String id = JsonPath.read(body(as(user, post(TICKETS), "{\"category\": \"BUG\", \"text\": \"  Не грузится лента  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.category").value("BUG"))
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.messages[0].text").value("Не грузится лента"))
                .andExpect(jsonPath("$.messages[0].fromStaff").value(false))
                .andReturn()), "$.id");

        as(user, get(TICKETS), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].messages").doesNotExist());

        // In the OPEN queue with who wrote it; the card has the masked phone.
        as(support, get(STAFF).param("status", "OPEN").param("limit", "100"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].preview", contains("Не грузится лента")));
        as(support, get(STAFF + "/" + id), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId(user)))
                .andExpect(jsonPath("$.phoneMasked").isNotEmpty())
                .andExpect(jsonPath("$.testAccount").value(false));

        as(support, post(STAFF + "/" + id + "/messages"), "{\"text\": \"Обновите приложение\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.messages[1].fromStaff").value(true));
        PushStub.Push push = PushStub.next(device, 5_000);
        assertNotNull(push, "the user gets a push about the answer");
        assertTrue(push.body().contains("support_reply") && push.body().contains(id));
        assertFalse(push.body().contains("Обновите"), "no message text in the push");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE entity_type = 'SUPPORT_TICKET' AND entity_id = ? AND action = 'REPLY'",
                Integer.class, id));

        as(user, get(TICKETS + "/" + id), null).andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.messages[1].text").value("Обновите приложение"));

        as(support, post(STAFF + "/" + id + "/close"), null).andExpect(jsonPath("$.status").value("CLOSED"));
        as(support, get(STAFF).param("status", "CLOSED").param("limit", "100"), null)
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')]", hasSize(1)));

        // A user message reopens even a closed ticket.
        as(user, post(TICKETS + "/" + id + "/messages"), "{\"text\": \"Не помогло\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.messages", hasSize(3)));
    }

    @Test
    void othersGet404AndNonStaffGet403() throws Exception {
        String owner = login(newPhone());
        String stranger = login(newPhone());
        String id = createTicket(owner);

        as(stranger, get(TICKETS + "/" + id), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
        as(stranger, post(TICKETS + "/" + id + "/messages"), "{\"text\": \"чужое\"}").andExpect(status().isNotFound());
        as(stranger, get(TICKETS), null).andExpect(jsonPath("$", hasSize(0)));

        as(stranger, get(STAFF), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_REQUIRED"));
        as(stranger, get(STAFF + "/" + id), null).andExpect(status().isForbidden());
        as(stranger, post(STAFF + "/" + id + "/messages"), "{\"text\": \"x\"}").andExpect(status().isForbidden());
        as(stranger, post(STAFF + "/" + id + "/close"), null).andExpect(status().isForbidden());
        as(staff("CONTENT_EDITOR"), get(STAFF), null).andExpect(status().isForbidden());

        // Moderators and admins are staff too.
        as(staff("MODERATOR"), get(STAFF + "/" + id), null).andExpect(status().isOk());
        admin(get(STAFF + "/" + id), null).andExpect(status().isOk());
        admin(get(STAFF + "/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
        admin(get(STAFF).param("status", "WHATEVER"), null).andExpect(status().isBadRequest());
    }

    @Test
    void validationRateLimitAndAccountDeletion() throws Exception {
        String user = login(newPhone());
        as(user, post(TICKETS), "{\"category\": \"BUG\", \"text\": \"   \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.text").exists());
        as(user, post(TICKETS), "{\"text\": \"без темы\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.category").exists());
        as(user, post(TICKETS), "{\"category\": \"NOPE\", \"text\": \"x\"}").andExpect(status().isBadRequest());
        as(user, post(TICKETS), "{\"category\": \"BUG\", \"text\": \"" + "a".repeat(4001) + "\"}")
                .andExpect(status().isBadRequest());

        for (int i = 0; i < 5; i++) {
            createTicket(user);
        }
        as(user, post(TICKETS), "{\"category\": \"OTHER\", \"text\": \"шестое\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        String accountId = accountId(user);
        as(user, delete("/api/v1/me"), null).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM support_tickets WHERE account_id = ?::uuid",
                Integer.class, accountId));
    }

    private String createTicket(String token) throws Exception {
        return JsonPath.read(body(as(token, post(TICKETS), "{\"category\": \"ACCOUNT\", \"text\": \"Помогите\"}")
                .andExpect(status().isCreated()).andReturn()), "$.id");
    }

    private String staff(String role) throws Exception {
        String token = login(newPhone());
        admin(put("/api/v1/admin/accounts/" + accountId(token) + "/roles/" + role), null).andExpect(status().isOk());
        return token;
    }
}
