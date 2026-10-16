package com.dating.discovery.api;

import java.util.Set;

/**
 * @param active false when the list is saved but not applied (premium is not active)
 */
public record ExcludedGoalsDto(Set<String> goalCodes, boolean active) {
}
