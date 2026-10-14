package com.dating.profiles.persistence;

import com.dating.profiles.domain.CommunicationTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CommunicationTagRepository extends JpaRepository<CommunicationTag, UUID> {

    List<CommunicationTag> findAllByActiveTrueOrderBySortOrderAsc();

    List<CommunicationTag> findAllByCodeInAndActiveTrue(Collection<String> codes);
}
