package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.ledger.LedgerEntry;
import com.gongmoju.account.domain.ledger.LedgerEntryRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// LedgerEntryRepository의 JPA 구현. 응용 계층은 도메인 인터페이스만 써서 delete()를 부를 수 없다
public interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntry, UUID>, LedgerEntryRepository {
}
