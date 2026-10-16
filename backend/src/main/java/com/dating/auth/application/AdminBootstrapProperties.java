package com.dating.auth.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param bootstrapPhones phones that get the ADMIN role on login (env APP_ADMIN_BOOTSTRAP_PHONES, comma-separated)
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminBootstrapProperties(List<String> bootstrapPhones) {

    public AdminBootstrapProperties {
        bootstrapPhones = bootstrapPhones == null ? List.of()
                : bootstrapPhones.stream().filter(p -> p != null && !p.isBlank()).toList();
    }
}
