package com.gongmoju.account.application.account;

import com.gongmoju.account.domain.account.AccountRepository;
import com.gongmoju.account.domain.ledger.LedgerEntry;
import com.gongmoju.account.domain.ledger.LedgerEntryRepository;
import com.gongmoju.account.domain.ledger.LedgerEntryType;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.util.PageableUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

// 계좌 거래 내역(원장) 조회. 원장은 추가만 하므로 조회 전용 서비스로 둔다
@Service
@RequiredArgsConstructor
public class LedgerQueryService {

    // 정렬은 거래 시각만 (API 명세 1.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt");

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional(readOnly = true)
    public Page<LedgerEntryResult> list(UUID userId, UUID accountId, LedgerEntryType type, Pageable pageable) {
        // 남의 계좌와 없는 계좌는 같은 404 (계좌 상세 조회와 같은 규칙)
        accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않는 계좌입니다.", "accountId"));
        Pageable stable = PageableUtils.toStablePageable(pageable, SORTABLE_FIELDS);
        // type이 없으면 전체, 있으면 해당 거래 종류만 조회한다
        Page<LedgerEntry> entries = type == null
                ? ledgerEntryRepository.findByAccount_Id(accountId, stable)
                : ledgerEntryRepository.findByAccount_IdAndType(accountId, type, stable);
        return entries.map(LedgerEntryResult::from);
    }
}
