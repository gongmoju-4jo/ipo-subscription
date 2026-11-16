package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.margin.MarginVoidIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface MarginVoidIntentJpaRepository extends JpaRepository<MarginVoidIntent, UUID> {

    // 엔티티와 계좌 프록시를 만들지 않고 청약 ID(PK)만 읽는다
    @Query("select v.subscriptionId from MarginVoidIntent v where v.subscriptionId in :ids")
    List<UUID> findSubscriptionIdsIn(@Param("ids") Collection<UUID> ids);
}
