package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.account.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

// 소유자(user)를 같이 읽어 계좌마다 추가 조회가 나가지 않게 한다
interface AccountJpaRepository extends JpaRepository<Account, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<Account> findByRequestId(UUID requestId);

    @EntityGraph(attributePaths = "user")
    Page<Account> findByUserId(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Optional<Account> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE) // 비관적 쓰기 락. DB 행 잠금
    @Query("select a from Account a where a.id = :id") // JPQL로 직접 작성
    Optional<Account> findByIdForUpdate(@Param("id") UUID id); // 계좌가 없을 수 있으므로 optional
}
