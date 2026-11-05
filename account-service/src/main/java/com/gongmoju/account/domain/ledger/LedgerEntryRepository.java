package com.gongmoju.account.domain.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

// 원장 저장소. 추가와 조회만 있음 (수정, 삭제 없음). 구현은 infrastructure/persistence (JPA)
public interface LedgerEntryRepository {

    // 원장 기록을 DB에 저장한다.
    LedgerEntry save(LedgerEntry entry);

    // 입금 멱등 확인용.
    // 동일한 입금 요청의 중복 처리를 막기 위해 requestId로 기존 원장을 조회한다.
    Optional<LedgerEntry> findByRequestId(UUID requestId);

    // 계좌 거래 내역 조회용. 계좌 소유자 확인은 응용 계층이 먼저 한다
    Page<LedgerEntry> findByAccount_Id(UUID accountId, Pageable pageable);
}
