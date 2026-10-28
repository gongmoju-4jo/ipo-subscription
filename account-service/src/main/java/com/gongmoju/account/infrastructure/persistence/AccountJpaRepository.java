package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.account.Account;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// 소유자(user)를 같이 읽어 계좌마다 추가 조회가 나가지 않게 한다
interface AccountJpaRepository extends JpaRepository<Account, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<Account> findByRequestId(UUID requestId);
}
