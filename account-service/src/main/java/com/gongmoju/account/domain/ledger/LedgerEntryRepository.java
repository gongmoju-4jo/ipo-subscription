package com.gongmoju.account.domain.ledger;

import java.util.Optional;
import java.util.UUID;

// 원장 저장소. 추가와 조회만 있음 (수정, 삭제 없음). 구현은 infrastructure/persistence (JPA)
public interface LedgerEntryRepository {

    // 원장 기록을 DB에 저장한다.
    LedgerEntry save(LedgerEntry entry);

    // 입금 멱등 확인용.
    // 동일한 입금 요청의 중복 처리를 막기 위해 requestId로 기존 원장을 조회한다.
    Optional<LedgerEntry> findByRequestId(UUID requestId);
}
