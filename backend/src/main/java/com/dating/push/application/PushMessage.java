package com.dating.push.application;

import java.util.Map;

/**
 * A notification. Texts are generic on purpose: they show on the lock screen, so no names or message text.
 *
 * @param data string pairs for the app (type, matchId) to open the right screen
 */
public record PushMessage(String title, String body, Map<String, String> data) {

    /** Request body of the FCM HTTP v1 send API; RuStore Push accepts the same format. */
    Map<String, Object> v1Body(String token) {
        return Map.of("message", Map.of(
                "token", token,
                "notification", Map.of("title", title, "body", body),
                "data", data));
    }
}
