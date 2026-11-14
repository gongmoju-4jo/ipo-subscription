package com.gongmoju.account.infrastructure.scheduling;

import com.gongmoju.account.application.reconciliation.LedgerReconciliationResult;
import com.gongmoju.account.application.reconciliation.LedgerReconciliationService;
import com.gongmoju.account.domain.reconciliation.LedgerCheckResult;
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
    // ERROR는 검사 단위라 Slack 알림의 ERROR 건수는 최대 검사 종류 수이고, 불일치 건수만큼 증가하지 않는다
    // 대상 ID, 상세는 조회한 불일치(검사당 최대 SAMPLE_LIMIT건)만 기록하고, 나머지는 생략 건수로 기록한다
    private void logMismatches(LedgerReconciliationResult result, long elapsedMillis) {
        log.warn("[원장 대사] 불일치 총 {}건. 자동으로 수정하지 않으니 운영자 확인 필요. 실행 시간 {}ms, 검사별 불일치 건수: {}",
                result.totalCount(), elapsedMillis, countsByCheck(result));

        for (LedgerCheckResult checkResult : result.checkResults()) {
            if (checkResult.totalCount() > 0) {
                LedgerReconciliationCheck check = checkResult.check();
                log.error("[원장 대사] 불일치 검사={}({}) {}건. 불일치 발생 대상 ID: {}",
                        check, check.getDescription(), checkResult.totalCount(), targetIds(checkResult));
                logDetails(checkResult);
            }
        }
    }

    // 검사 1개의 상세 WARN. 번호의 분모는 전체 불일치 건수
    private void logDetails(LedgerCheckResult checkResult) {
        List<LedgerMismatch> samples = checkResult.samples();
        for (int i = 0; i < samples.size(); i++) {
            LedgerMismatch mismatch = samples.get(i);
            log.warn("[원장 대사] 불일치 상세 {}/{}: 검사={}({}), accountId={}, marginId={}, {}",
                    i + 1, checkResult.totalCount(), mismatch.check(), mismatch.check().getDescription(),
                    mismatch.accountId(), mismatch.marginId(), mismatch.detail());
        }
        if (checkResult.omittedCount() > 0) {
            log.warn("[원장 대사] 불일치 상세 {}건 생략 (검사={}). 검사별 조회 상한 {}건",
                    checkResult.omittedCount(), checkResult.check(), LedgerReconciliationService.SAMPLE_LIMIT);
        }
    }

    // 예: "a1b2..., c3d4..." / 조회 상한 초과 시 "a1b2..., ... 외 5건"
    private String targetIds(LedgerCheckResult checkResult) {
        String ids = checkResult.samples().stream()
                .map(mismatch -> String.valueOf(mismatch.targetId()))
                .collect(Collectors.joining(", "));
        return checkResult.omittedCount() > 0 ? ids + " 외 " + checkResult.omittedCount() + "건" : ids;
    }

    // 예: "MARGIN_BALANCE=0, LEDGER_SUM=1, MARGIN_PENDING_LEDGER=0, MARGIN_STATUS_LEDGER=0"
    private String countsByCheck(LedgerReconciliationResult result) {
        return Arrays.stream(LedgerReconciliationCheck.values())
                .map(check -> check.name() + "=" + result.count(check))
                .collect(Collectors.joining(", "));
    }
}
