package com.dating.safety.domain;

import java.util.List;

/**
 * Consequences of a confirmed report reason. {@code steps.get(n)} applies to the (n+1)-th sanctioned violation
 * within {@code windowDays}; past the end the last step repeats.
 *
 * @param holdOnReports hide the profile while this many different people have open reports with this reason
 *                      (null = never)
 */
public record SanctionPolicy(String reason, int windowDays, Integer holdOnReports, List<Step> steps) {

    public record Step(List<StepRestriction> restrictions) {
    }

    /** @param hours null = until lifted */
    public record StepRestriction(RestrictionKind kind, Integer hours) {
    }

    public Step step(int priorViolations) {
        return steps.isEmpty() ? new Step(List.of()) : steps.get(Math.min(priorViolations, steps.size() - 1));
    }
}
