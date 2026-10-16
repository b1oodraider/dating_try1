package com.dating.push.application;

import com.dating.push.domain.PushProvider;

public interface PushSender {

    enum Result {
        SENT,
        /** The token is unknown or expired: the device must be forgotten. */
        INVALID_TOKEN,
        /** Not sent (provider off, network, quota, server error); the device is kept. */
        FAILED
    }

    PushProvider provider();

    Result send(String token, PushMessage message);
}
