package com.gongmoju.account.domain.account;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 계좌 저장소. 구현은 infrastructure/persistence (JPA)
public interface AccountRepository {

    /**
     * 저장하고 바로 DB에 반영한다. 유니크 위반(같은 requestId 동시 요청, 계좌번호 충돌)을 호출한 트랜잭션 안에서 알 수 있다.
     *
     * @param account 저장할 계좌
     * @return 저장된 계좌
     */
    Account saveAndFlush(Account account);

    Optional<Account> findByRequestId(UUID requestId);

    Page<Account> findByUserId(UUID userId, Pageable pageable);

    /**
     * 본인 계좌만 찾는다. 없는 계좌와 남의 계좌를 구분하지 않는다 (존재 숨김).
     *
     * @param accountId 계좌 ID
     * @param userId    요청자 회원 ID
     * @return 본인 계좌
     */
    Optional<Account> findByIdAndUserId(UUID accountId, UUID userId);

    // 계좌 행에 쓰기 잠금을 획득하여 동시 잔고 변경 방지
    // 잠금은 트랜잭션이 종료될 때 해제됨
    Optional<Account> findByIdForUpdate(UUID accountId);

    /**
     * 여러 계좌에 쓰기 잠금을 한 번에 획득한다 (한 트랜잭션에서 여러 건을 처리할 때, 쿼리 1회).
     * 계좌 ID 순으로 잠가서 같은 방식으로 여러 계좌를 잠그는 트랜잭션끼리 서로 기다리며 멈추지 않게 함
     * 없는 계좌는 결과에 없다.
     *
     * @param accountIds 계좌 ID (비어 있으면 안 됨)
     * @return 잠근 계좌 (ID 순)
     */
    List<Account> findAllByIdForUpdate(Collection<UUID> accountIds);
}
