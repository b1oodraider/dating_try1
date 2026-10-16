package com.dating.push.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Push providers. A provider without credentials is off: its devices are kept but nothing is sent to them.
 */
@ConfigurationProperties(prefix = "app.push")
public record PushProperties(@DefaultValue Rustore rustore, @DefaultValue Fcm fcm) {

    /**
     * @param projectId    RuStore console project id
     * @param serviceToken RuStore console service token
     */
    public record Rustore(String projectId, String serviceToken,
                          @DefaultValue("https://vkpns.rustore.ru") String baseUrl) {
        public boolean configured() {
            return projectId != null && !projectId.isBlank() && serviceToken != null && !serviceToken.isBlank();
        }
    }

    /**
     * @param credentialsFile path to the Firebase service account JSON (project_id, client_email, private_key, token_uri)
     */
    public record Fcm(String credentialsFile, @DefaultValue("https://fcm.googleapis.com") String baseUrl) {
        public boolean configured() {
            return credentialsFile != null && !credentialsFile.isBlank();
        }
    }
}
