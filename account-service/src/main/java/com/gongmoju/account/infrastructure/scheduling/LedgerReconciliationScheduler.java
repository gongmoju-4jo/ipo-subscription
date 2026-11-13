package com.gongmoju.account.infrastructure.scheduling;

import com.gongmoju.account.application.reconciliation.LedgerReconciliationResult;
import com.gongmoju.account.application.reconciliation.LedgerReconciliationService;
import com.gongmoju.account.domain.reconciliation.LedgerMismatch;
import com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// 원장 대사를 주기적으로 실행하고 결과를 로그로 기록한다 (#132). 실행 시각은 ledger.reconciliation.cron
// 불일치 0건이면 INFO 1줄, 불일치가 있으면 검사별 ERROR (건수, 불일치 발생 대상 ID) + 상세 WARN으로 기록한다
// ERROR 로그는 Grafana ErrorLogDetected가 Slack 알림으로 발송한다 (#136)
// 로그는 모두 "[원장 대사]"로 시작해서 Slack 알림 후 로그 검색 1번으로 조회 가능하다
// 단일 인스턴스를 전제로 한다. 인스턴스가 여러 개면 인스턴스 수만큼 실행된다 (조회만 해서 데이터 영향은 없고, 알림이 중복 발송된다)
// 수평 확장 시 분산 락을 reconcile()에 추가한다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ledger.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
class LedgerReconciliationScheduler {

    // 검사별 상세 로그 라인 수, 불일치 발생 대상 ID 개수 상한. 버그 배포로 전체 계좌가 불일치하는 경우에도 로그 크기가 이 값 기준으로 제한된다
    static final int DETAIL_LOG_LIMIT = 100;

    private final LedgerReconciliationService reconciliationService;

    @Scheduled(cron = "${ledger.reconciliation.cron}", zone = "Asia/Seoul")
    void reconcile() {
        long startNanos = System.nanoTime();
        LedgerReconciliationResult result;
        try {
            result = reconciliationService.reconcile();
        } catch (RuntimeException e) {
            // 예외를 처리하지 않으면 다음 실행 시각까지 스케줄은 유지되지만 원인 메시지가 불명확해서, 접두어를 추가해 기록한다
            log.error("[원장 대사] 실행 실패. 다음 실행 시각에 다시 실행함", e);
            return;
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        if (result.consistent()) {
            log.info("[원장 대사] 불일치 없음. 실행 시간 {}ms, 검사별 불일치 건수: {}", elapsedMillis, countsByCheck(result));
            return;
        }
        logMismatches(result, elapsedMillis);
    }

    // 요약 WARN 1줄 → 불일치가 있는 검사마다 ERROR 1줄 (건수, 불일치 발생 대상 ID) + 상세 WARN (기대값, 실제값)
    // ERROR는 검사 단위라 Slack 알림의 ERROR 건수는 최대 검사 수(4)이고, 불일치 건수만큼 증가하지 않는다
    private void logMismatches(LedgerReconciliationResult result, long elapsedMillis) {
        log.warn("[원장 대사] 불일치 총 {}건. 자동으로 수정하지 않으니 운영자 확인 필요. 실행 시간 {}ms, 검사별 불일치 건수: {}",
                result.mismatches().size(), elapsedMillis, countsByCheck(result));

        for (LedgerReconciliationCheck check : LedgerReconciliationCheck.values()) {
            List<LedgerMismatch> ofCheck = result.mismatchesOf(check);
            if (!ofCheck.isEmpty()) {
                log.error("[원장 대사] 불일치 검사={}({}) {}건. 불일치 발생 대상 ID: {}",
                        check, check.getDescription(), ofCheck.size(), targetIds(ofCheck));
                logDetails(ofCheck);
            }
        }
    }

    // 검사 1개의 상세 WARN. 상한은 검사별로 적용해서, 이전 검사의 불일치가 많아도 이후 검사의 상세가 누락되지 않는다
    private void logDetails(List<LedgerMismatch> ofCheck) {
        int logged = Math.min(ofCheck.size(), DETAIL_LOG_LIMIT);
        for (int i = 0; i < logged; i++) {
            LedgerMismatch mismatch = ofCheck.get(i);
            log.warn("[원장 대사] 불일치 상세 {}/{}: 검사={}({}), accountId={}, marginId={}, {}",
                    i + 1, ofCheck.size(), mismatch.check(), mismatch.check().getDescription(),
                    mismatch.accountId(), mismatch.marginId(), mismatch.detail());
        }
        if (ofCheck.size() > logged) {
            log.warn("[원장 대사] 불일치 상세 {}건 생략 (검사={}). 검사별 상세 로그 상한 {}건",
                    ofCheck.size() - logged, ofCheck.get(0).check(), DETAIL_LOG_LIMIT);
        }
    }

    // 예: "a1b2..., c3d4..." / 상한 초과 시 "a1b2..., ... 외 5건"
    private String targetIds(List<LedgerMismatch> mismatches) {
        String ids = mismatches.stream()
                .limit(DETAIL_LOG_LIMIT)
                .map(mismatch -> String.valueOf(mismatch.targetId()))
                .collect(Collectors.joining(", "));
        int omitted = mismatches.size() - DETAIL_LOG_LIMIT;
        return omitted > 0 ? ids + " 외 " + omitted + "건" : ids;
    }

    // 예: "MARGIN_BALANCE=0, LEDGER_SUM=1, MARGIN_PENDING_LEDGER=0, MARGIN_STATUS_LEDGER=0"
    private String countsByCheck(LedgerReconciliationResult result) {
        return Arrays.stream(LedgerReconciliationCheck.values())
                .map(check -> check.name() + "=" + result.count(check))
                .collect(Collectors.joining(", "));
    }
}
