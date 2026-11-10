package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface IpoJpaRepository extends JpaRepository<Ipo, UUID> {

    List<Ipo> findByStatus(IpoStatus status);

    List<Ipo> findByStatusAndSubscriptionEndAtLessThanEqual(IpoStatus status, Instant endAt);
}
