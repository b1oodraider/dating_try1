package com.dating.push.application;

import com.dating.push.domain.PushProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;

/**
 * RuStore Push REST API: POST {base}/v1/projects/{projectId}/messages:send with the console service token.
 * 400 INVALID_ARGUMENT means a bad token, 404 NOT_FOUND an expired one (our payload is fixed, so 400 is the token).
 */
@Component
public class RuStorePushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(RuStorePushSender.class);

    private final PushProperties.Rustore properties;
    private final JsonMapper jsonMapper;

    public RuStorePushSender(PushProperties properties, JsonMapper jsonMapper) {
        this.properties = properties.rustore();
        this.jsonMapper = jsonMapper;
    }

    @Override
    public PushProvider provider() {
        return PushProvider.RUSTORE;
    }

    @Override
    public Result send(String token, PushMessage message) {
        if (!properties.configured()) {
            return Result.FAILED;
        }
        String url = properties.baseUrl() + "/v1/projects/" + properties.projectId() + "/messages:send";
        HttpResponse<String> response = PushHttp.post(url, "application/json", properties.serviceToken(),
                jsonMapper.writeValueAsString(message.v1Body(token)));
        if (response == null) {
            return Result.FAILED;
        }
        int status = response.statusCode();
        if (status == 200) {
            return Result.SENT;
        }
        if (status == 400 || status == 404) {
            return Result.INVALID_TOKEN;
        }
        log.warn("RuStore push failed: HTTP {} {}", status, response.body());
        return Result.FAILED;
    }
}
