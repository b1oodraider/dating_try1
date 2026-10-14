package com.dating.discovery.persistence;

import com.dating.discovery.domain.DiscoveryPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DiscoveryPreferencesRepository extends JpaRepository<DiscoveryPreferences, UUID> {
}
