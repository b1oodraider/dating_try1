package com.dating.auth.persistence;

import com.dating.auth.domain.AuthChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthChallengeRepository extends JpaRepository<AuthChallenge, UUID> {
    Optional<AuthChallenge> findTopByPhoneHashOrderByCreatedAtDesc(String phoneHash);
}
