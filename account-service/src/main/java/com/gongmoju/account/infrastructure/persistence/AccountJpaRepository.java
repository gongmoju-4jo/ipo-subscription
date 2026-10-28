package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.account.domain.account.AccountRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

// AccountRepository의 JPA 구현. 도메인 인터페이스와 시그니처가 같은 메서드를 Spring Data가 구현한다
public interface AccountJpaRepository extends JpaRepository<Account, UUID>, AccountRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE) // 비관적 쓰기 락. DB 행 잠금
    @Query("select a from Account a where a.id = :id") // JPQL로 직접 작성
    Optional<Account> findByIdForUpdate(@Param("id") UUID id); // 계좌가 없을 수 있으므로 optional
}
