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

    // 마감용. 공모주 행을 잠가(FOR UPDATE) 같은 공모주 행을 잡은 다른 트랜잭션이 끝난 뒤에 읽는다 (D-29)
    Optional<Ipo> findByIdForUpdate(UUID ipoId);

    // 접수용. 공모주 행을 공유 잠금(FOR SHARE)으로 읽어 접수끼리는 함께 진행하고, 마감(FOR UPDATE)과는 순서를 정한다 (D-29)
    Optional<Ipo> findByIdForShare(UUID ipoId);

    // 마감 대상 조회용. 해당 상태이고 청약 마감 시각이 기준 시각과 같거나 앞인 공모주
    List<Ipo> findByStatusAndSubscriptionEndAtLessThanEqual(IpoStatus status, Instant endAt);
}
