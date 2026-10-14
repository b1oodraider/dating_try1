package com.dating.profiles.api;

import com.dating.profiles.domain.RelationshipGoal;

public record GoalDto(String code, String label, int sortOrder) {

    public static GoalDto from(RelationshipGoal goal) {
        return new GoalDto(goal.getCode(), goal.getLabel(), goal.getSortOrder());
    }
}
