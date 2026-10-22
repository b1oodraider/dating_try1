package com.dating;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The purchase stub grants the product right away (no payment yet). */
class PurchaseStubIntegrationTest extends AdminTestSupport {

    @Test
    void stubPurchasesGrantPremiumAndItems() throws Exception {
        String me = login(newPhone());
        as(me, post("/api/v1/me/purchases/stub"), "{\"product\": \"PREMIUM_MONTH\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.premium.active").value(true));
        as(me, post("/api/v1/me/purchases/stub"), "{\"product\": \"SUPERLIKE_PACK\"}")
                .andExpect(jsonPath("$.items[?(@.code == 'SUPERLIKE')].quantity", contains(5 + 0)));
        as(me, post("/api/v1/me/purchases/stub"), "{\"product\": \"BOOST_PACK\"}")
                .andExpect(status().isOk());
        as(me, post("/api/v1/me/purchases/stub"), "{\"product\": \"GOLD\"}").andExpect(status().isNotFound());
    }
}
