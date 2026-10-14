package com.dating.profiles.persistence;

import com.dating.profiles.domain.RelationshipGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface RelationshipGoalRepository extends JpaRepository<RelationshipGoal, String> {

    List<RelationshipGoal> findAllByActiveTrueOrderBySortOrderAsc();

    List<RelationshipGoal> findAllByCodeInAndActiveTrue(Collection<String> codes);
}
