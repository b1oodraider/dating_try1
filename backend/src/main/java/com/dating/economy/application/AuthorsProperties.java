package com.dating.economy.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param defaultRevenueSharePercent share of sales a new author gets unless the admin sets another one
 */
@ConfigurationProperties(prefix = "app.authors")
public record AuthorsProperties(@DefaultValue("30") int defaultRevenueSharePercent) {

    public AuthorsProperties {
        if (defaultRevenueSharePercent < 0 || defaultRevenueSharePercent > 100) {
            throw new IllegalStateException("app.authors.default-revenue-share-percent must be in 0..100");
        }
    }
}
