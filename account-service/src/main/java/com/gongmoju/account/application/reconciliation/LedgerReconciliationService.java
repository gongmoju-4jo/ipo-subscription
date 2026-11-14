package com.gongmoju.account.application.reconciliation;

import com.gongmoju.account.domain.reconciliation.LedgerCheckResult;
import com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck;
import com.gongmoju.account.domain.reconciliation.LedgerReconciliationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

// 계좌 잔고, 증거금, 원장이 서로 일치하는지 검사한다 (원장 대사, #132)
// 조회만 하고 수정하지 않는다. 배치는 계좌와 원장 중 어느 쪽이 정답인지 판단 불가능해서, 정정은 사람이 원인을 확인한 후 반대 거래로 처리한다
@Service
@RequiredArgsConstructor
public class LedgerReconciliationService {

    // 검사별로 조회하는 불일치 상한 (로그의 불일치 발생 대상 ID, 상세도 이 건수까지)
    // 전체 불일치가 212만 건이어도 메모리에 적재되는 행은 검사당 이 값으로 제한되고, 전체 건수는 DB에서 집계한다
    public static final int SAMPLE_LIMIT = 100;

    private final LedgerReconciliationRepository repository;

    // 모든 검사를 같은 스냅샷(REPEATABLE READ)에서 조회한다
    // 잔고 변경과 원장 기록은 같은 트랜잭션에서 커밋되므로, 검사 중에 거래가 커밋돼도 한쪽만 반영된 상태를 조회하지 않는다
    // 락을 획득하지 않아 인출, 정산 처리를 지연시키지 않는다
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LedgerReconciliationResult reconcile() {
        List<LedgerCheckResult> checkResults = Arrays.stream(LedgerReconciliationCheck.values())
                .map(check -> repository.findMismatches(check, SAMPLE_LIMIT))
                .toList();
        return new LedgerReconciliationResult(checkResults);
    }
}
