package com.gongmoju.account.application.account;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.account.domain.account.AccountRepository;
import com.gongmoju.account.domain.ledger.LedgerEntry;
import com.gongmoju.account.domain.ledger.LedgerEntryRepository;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

// F-14 가상 입금. 계좌 락 → 본인 확인 → 중복 확인 → 잔고 변경 → 원장 저장을 한 트랜잭션에서 처리한다

/**
 * 가상 입금 서비스.
 * 계좌 잠금, 본인 확인, 중복 입금 검사, 잔고 변경 및 원장 저장을
 * 하나의 트랜잭션에서 처리한다.
 */
@Service
@RequiredArgsConstructor
public class DepositService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * 가상 입금을 처리한다.
     *
     * @param accountId 입금할 계좌 ID
     * @param userId    요청한 사용자 ID
     * @param amount    입금 금액
     * @param requestId 중복 입금 방지를 위한 요청 ID
     * @return 신규 입금 또는 기존 입금의 처리 결과
     */
    @Transactional
    public DepositResult deposit(UUID accountId, UUID userId, long amount, UUID requestId) {
        // requestId가 null이면 findByRequestId가 request_id IS NULL인 증거금 원장을 찾을 수 있어서 먼저 막는다
        if (requestId == null) {
            throw new IllegalArgumentException("입금은 requestId가 필요합니다.");
        }

        // 잔고 변경 전 락을 먼저 건다.
        // 이미 조회한 엔티티는 JPA 캐시에 남아 있을 수 있으므로 잠금 조회를 먼저 수행
        Account account = accountRepository.findByIdForUpdate(accountId)
                .filter(
                        found -> found.getUser().getId().equals(userId)
                ) // 요청자 계좌가 아니면 404
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "계좌를 찾을 수 없습니다.", "accountId"
                ));

        // 같은 요청 ID로 이미 처리한 입금이 있다면 기존 결과를 반환한다.
        Optional<LedgerEntry> existing = ledgerEntryRepository.findByRequestId(requestId);
        if (existing.isPresent()) { // 처리한 적 있는 경우
            return replay(existing.get(), accountId, amount);
        }

        // 입금: 예수금 잔고 증가. 입금 후 예수금이 long 범위(약 922경 원)를 넘으면 500이 아니라 400으로 거절한다
        // 실제 돈이라면 닿을 수 없는 값이지만, 가상 입금은 사용자가 금액을 직접 정하므로 서버 오류로 새지 않게 막는다
        // (1회 상한이 정해지지 않아 @Max를 두지 않았고, 잔고를 알아야 알 수 있어서 요청 검증 다음 단계인 여기서 막는다)
        try {
            account.deposit(amount);
        } catch (ArithmeticException e) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR, "입금 후 예수금이 허용 범위를 넘습니다.", "amount"
            );
        }
        // 변경된 잔고 기준으로 입금 원장 생성 후 저장
        LedgerEntry entry = ledgerEntryRepository.save(LedgerEntry.deposit(account, amount, requestId));
        // 신규 입금 처리 결과 반환
        return DepositResult.of(entry, true);
    }

    /**
     * 중복 입금 요청에 대해 기존 처리 결과를 반환한다.
     * 기존 요청과 계좌 또는 금액이 다르면 예외를 발생시킨다.
     *
     * @param entry     기존 입금 원장 기록
     * @param accountId 요청한 계좌 ID
     * @param amount    요청한 입금 금액
     * @return 기존 입금 처리 결과
     */
    private DepositResult replay(LedgerEntry entry, UUID accountId, long amount) {
        if (!entry.getAccount().getId().equals(accountId) ||
                entry.getAmount() != amount) {
            throw new BusinessException(
                    CommonErrorCode.DUPLICATE_RESOURCE, "같은 requestId로 다른 입금 요청이 있습니다.", "requestId"
            );
        }
        return DepositResult.of(entry, false);
    }
}
