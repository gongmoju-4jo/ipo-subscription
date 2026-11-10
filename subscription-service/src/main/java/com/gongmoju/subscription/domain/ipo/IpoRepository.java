package com.gongmoju.subscription.domain.ipo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 공모주 저장소. 구현은 infrastructure/persistence (JPA)
public interface IpoRepository {

    Page<Ipo> findAll(Pageable pageable);

    Optional<Ipo> findById(UUID ipoId);

    // 상태가 같은 공모주 전체. 게이트웨이의 청약 중 공모주 목록(내부 API 4.4)용이라 OPEN처럼 몇 개뿐인 상태에만 쓴다
    List<Ipo> findByStatus(IpoStatus status);

    // 마감 대상 조회용. 해당 상태이고 청약 마감 시각이 기준 시각과 같거나 앞인 공모주
    List<Ipo> findByStatusAndSubscriptionEndAtLessThanEqual(IpoStatus status, Instant endAt);
}
