package com.dating.economy.application;

import com.dating.economy.domain.AllowancePeriod;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * What premium includes by default. Admins can override both lists without a release
 * (PremiumCompositionService); runtime code reads the composition from there, not from here.
 *
 * @param premiumFeatures   feature codes reserved for premium (checked via FeatureAccess)
 * @param premiumAllowances consumables periodically granted to premium users
 */
@ConfigurationProperties(prefix = "app.economy")
public record EconomyProperties(List<String> premiumFeatures, List<Allowance> premiumAllowances) {

    public EconomyProperties {
        premiumFeatures = premiumFeatures == null ? List.of() : List.copyOf(premiumFeatures);
        premiumAllowances = premiumAllowances == null ? List.of() : List.copyOf(premiumAllowances);
    }

    /**
     * @param mode TOP_UP: refill up to {@code quantity} (no hoarding); ADD: add {@code quantity} every period
     */
    public record Allowance(String itemCode, long quantity, AllowancePeriod period, Mode mode) {
        public Allowance {
            if (itemCode == null || itemCode.isBlank() || quantity <= 0) {
                throw new IllegalStateException("app.economy.premium-allowances: item-code and positive quantity are required");
            }
            period = period == null ? AllowancePeriod.WEEKLY : period;
            mode = mode == null ? Mode.TOP_UP : mode;
        }
    }

    public enum Mode {
        ADD,
        TOP_UP
    }
}
