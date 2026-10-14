package com.dating.productevents.persistence;

import com.dating.productevents.domain.ProductEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProductEventRepository extends JpaRepository<ProductEvent, UUID> {
}
