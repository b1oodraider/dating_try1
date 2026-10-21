package com.dating.profiles.persistence;

import com.dating.profiles.domain.Interest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface InterestRepository extends JpaRepository<Interest, String> {

    List<Interest> findAllByCodeInAndActiveTrue(Collection<String> codes);

    List<Interest> findAllByActiveTrue();
}
