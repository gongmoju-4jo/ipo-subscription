package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.margin.Margin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface MarginJpaRepository extends JpaRepository<Margin, UUID> {

    Optional<Margin> findBySubscriptionId(UUID subscriptionId);
}
