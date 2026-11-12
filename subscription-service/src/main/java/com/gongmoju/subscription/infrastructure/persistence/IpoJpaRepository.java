package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface IpoJpaRepository extends JpaRepository<Ipo, UUID> {

    List<Ipo> findByStatus(IpoStatus status);

    List<Ipo> findByStatusAndSubscriptionEndAtLessThanEqual(IpoStatus status, Instant endAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Ipo i where i.id = :id")
    Optional<Ipo> findByIdForUpdate(@Param("id") UUID id);
}
