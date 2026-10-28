package com.gongmoju.account.domain.account;

import java.util.Optional;
import java.util.UUID;

// 계좌 저장소. 구현은 infrastructure/persistence (JPA)
public interface AccountRepository {

    // 계좌 행에 쓰기 잠금을 획득하여 동시 잔고 변경 방지
    // 잠금은 트랜잭션이 종료될 때 해제됨
    Optional<Account> findByIdForUpdate(UUID id);
}
