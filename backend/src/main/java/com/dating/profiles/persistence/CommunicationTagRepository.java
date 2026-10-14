package com.dating.profiles.persistence;

import com.dating.profiles.domain.CommunicationTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface CommunicationTagRepository extends JpaRepository<CommunicationTag, UUID> {
    List<CommunicationTag> findAllByActiveTrueOrderBySortOrderAsc();
    Optional<CommunicationTag> findByCode(String code);
    Set<CommunicationTag> findAllByCodeIn(Set<String> codes);
}
